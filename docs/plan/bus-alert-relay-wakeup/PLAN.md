# PLAN — 버스 알림 relay 이벤트 wake-up (초단위 발송 지연 개선)

## 목표
enqueue → send 사이의 **구조적 relay 대기(최대 1 interval)** 를 이벤트 wake-up 으로 제거.
due→SENT p95: interval 2.395s → wake-up 0.889s (relay hop ~1.5s 제거). 정합성/전달보장 불변.

## 핵심 원칙 (설계 리뷰 확정)
- **outbox = source of truth, signal = 빨리 깨우는 힌트.** signal 유실돼도 fallback 이 복구 → 정합성은 여전히 outbox 소유.
- **in-process signal (Semaphore)**, pg LISTEN/NOTIFY 아님 — 단일 인스턴스, poller·relay 동일 JVM. NOTIFY 는 다중 인스턴스 확장 시 후보(문서 한 줄).
- **sweeper 는 유지(필수).** 구조 = relay 루프의 `await(sweepMs)` timeout 자체가 sweeper — 별도 @Scheduled 잡을 만들지 않고 같은 루프가 신호/타임아웃 둘 다 처리. timeout 경로가 곧 주기 재스캔이므로 "sweeper 제거"가 아니라 "루프에 통합". (원하면 별도 @Scheduled sweeper 로 분리 가능하나 이중 트리거가 relayOnce 에 동시 진입 → 낭비. 통합 권장.)
- **재시도 row 는 signal 밖 → sweeper 가 처리.** transient 실패 시 `scheduled_at = now + backoff`(미래)로 PENDING 복귀 → 새 enqueue 가 아니라 wake 신호 없음 → claimBatch(`scheduled_at <= now`)가 시간 지난 뒤에만 잡음 → 그 시점 wake 가 없으면 **await timeout(sweep, 1~5s)** 이 픽업. 재시도 픽업 지연 ≈ backoff + ≤sweepInterval. wake-up 은 "첫 시도"만 즉시화.

## 아키텍처
wake 는 **outbox row 를 만든 주체(EnqueueService)가 발행** — scheduler 결합 제거. 다른 producer 가
생겨도 wake 누락 없고, scheduler 는 outbox 내부를 몰라도 됨. dispatchDue 시그니처 불변.
```
BusAlertOutboxEnqueueService.enqueueTossArrival()  (@Transactional REQUIRES_NEW)
  enqueue 성공 → applicationEventPublisher.publishEvent(OutboxEnqueuedEvent)
                 (이 tx 에 바인딩됨)

RelayWakeListener (신규)
  @TransactionalEventListener(phase = AFTER_COMMIT)   // 커밋 후에만 wake → relay 가 조회 시 row 보임
  onEnqueued() → relayWaker.wake()

RelayWaker (신규, Semaphore(1) 병합)
  wake()                = synchronized { if (semaphore.availablePermits() == 0) semaphore.release(); }
                          → permit 최대 1개(coalescing). burst 100 wake → drain 1회로 병합.
  await(sweepMs): bool  = tryAcquire(sweepMs) — 신호 오면 true, 만료(=sweep 시점)면 false

BusAlertRelayLoop (신규, SmartLifecycle 스레드) — 기존 @Scheduled relay 대체
  while(running):
    relayWaker.await(sweepMs)          // 신호 or sweep timeout(=sweeper)
    if (!enabled || !activeHours) continue
    relayService.drainUntilEmpty()     // 밀린 backlog 전량 처리(아래)
```

## 정합성 레이스 방어 (구현 1순위 버그 지점)
1. **drain-until-empty (핵심 이유 = backlog)**: 한 번의 wake 가 batch 1개만 처리하면 **기존 backlog 의 나머지가 다음 sweep timeout 까지 밀린다**(1s 지연 재발). 그래서 `relayOnce()` 를 claim 0 될 때까지 반복해 밀린 것 전량 처리. (drain 중 들어온 row 를 "놓치는" 문제가 아니라, 한 batch 만 처리하면 나머지가 지연되는 문제.)
2. **coalescing + drain 중 신호**: burst N wake → permit 상한 1 → drain 1회 병합. drain 진행 중 새 enqueue+wake 오면 **permit 이 남아 다음 loop 에서 처리**(await 즉시 통과 → 재 drain). 신호를 "놓치지 않는 힌트"로 다룸 — 유실 없음.
3. **drainUntilEmpty 무한 점유 방어**: producer 가 계속 enqueue 하면 claim 이 0 안 나올 수 있음 → `while(claimed>0 && running && !interrupted && batches<maxBatches && elapsed<maxDrainMillis)`. yield 조건 만료 시 루프 상단 await 로 복귀(permit 남았으면 즉시 재진입). 재시도 row(scheduled_at 미래)는 claim 안 되니 정상 종료.
4. **커밋 가시성**: enqueue 는 REQUIRES_NEW → `@TransactionalEventListener(AFTER_COMMIT)` 가 그 tx 커밋 후 wake → relay 가 조회 시 row 반드시 보임.

## 변경 대상
1. `RelayWaker` (application/notification/service, 신규) — Semaphore(1), wake()/await(timeout). coalescing.
2. `OutboxEnqueuedEvent` + `RelayWakeListener` (신규) — EnqueueService 가 enqueue 성공 시 이벤트 발행,
   `@TransactionalEventListener(AFTER_COMMIT)` 리스너가 relayWaker.wake(). **scheduler/dispatch 무변경**.
3. `BusAlertOutboxEnqueueService` — enqueue 성공(true) 시 `publishEvent(OutboxEnqueuedEvent)` 한 줄 추가.
4. `BusAlertOutboxRelayService` — `drainUntilEmpty()` 추가(relayOnce 반복 + yield 가드). relayOnce 는 claim 처리 여부 boolean 반환.
5. `BusAlertRelayLoop` (infrastructure/.../scheduler/notifination, 신규, SmartLifecycle) — 루프 스레드. 기존 `BusAlertOutboxRelayScheduler`(@Scheduled) 제거.
6. `BusArrivalAlertProperties` — `outboxRelayIntervalMs` → `outboxSweepIntervalMs`(fallback sweep, 기본 1000~5000) + drain 가드(`outboxRelayMaxBatches`/`outboxDrainMaxMillis`). 관련 yaml.
   ※ dispatchDue 시그니처 불변 → 기존 부하/유닛 IT 영향 없음.

## 측정 (BusAlertRelayLatencyIT, 신규)
- 실 PostgreSQL 17. due alert seed → 파이프라인 통과 → enqueue timestamp vs SENT timestamp 차이 분포.
- **interval baseline**: relay 를 고정 간격으로만 호출(wake 미사용) → p95 ≈ interval 적층.
- **wakeup**: poller→wake→loop 즉시 drain → p95 ms 수준.
- 리포트: mode, p50/p95/max due→SENT ms. 목표 재현: interval 2.4s급 → wakeup <1s.

## 정직 경계 (포폴 서술용)
- **relay interval 을 줄인 게 아니라, outbox commit 이후 relay tick 대기를 제거**한 것. (간격 튜닝 아님 — 이벤트 기반 즉시 wake)
- 제거 범위 = **적재→발송 hop(≤1 interval)만**. 버스 데이터 신선도(~15-30s) + polling 감지 지연은 별도 한계로 남는다. "초단위 발송 보장"의 보장 범위 = 적재→발송 구간 + at-least-once.
- 재시도 지연 하한 = sweep 간격(재시도 row 는 wake 밖).

## 리스크
- SmartLifecycle 스레드 lifecycle — stop() 에서 running=false + interrupt + join(타임아웃). await 는 interrupt 로 즉시 탈출.
- drainUntilEmpty 무한 점유 → yield 가드(running/interrupt/maxBatches/maxDrainMillis)로 방어. 만료 시 await 복귀.
- @TransactionalEventListener 는 활성 tx 필요 → enqueue REQUIRES_NEW 안에서 publish 하므로 충족. 리스너 예외가 커밋 롤백 안 하게 wake() 는 예외 삼키기.
- 테스트에서 relay 루프 스레드 대신 relayService.drainUntilEmpty() 직접 호출로 결정론 확보(스레드 타이밍 회피).

## 티어: M (이벤트 wake-up, 신규 5 + 측정 IT, diff 예측 가능. dispatchDue 시그니처 불변)
```
1. RelayWaker(Semaphore coalescing) 신규
2. OutboxEnqueuedEvent + RelayWakeListener(@TransactionalEventListener AFTER_COMMIT) 신규
3. EnqueueService enqueue 성공 시 publishEvent 한 줄
4. relayService.drainUntilEmpty(yield 가드) + relayOnce boolean 반환
5. BusAlertRelayLoop(SmartLifecycle) 신규, 기존 @Scheduled relay 제거
6. properties sweep rename + drain 가드 + yaml
7. BusAlertRelayLatencyIT (interval vs wakeup due→SENT p95) + 전체 알림 스위트 회귀
```
