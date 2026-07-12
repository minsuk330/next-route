# 버스 알림 relay 이벤트 wake-up

## 뭘 하나
enqueue 직후 relay 를 **즉시 깨워** 발송한다. 기존엔 relay 가 고정 간격(1s)으로만 돌아
enqueue→send 사이에 최대 1 interval 의 구조적 대기가 있었다.

## 왜
초단위 알림에서 relay tick 대기가 지연의 큰 몫. due→SENT p95 측정: interval 2.395s →
즉시 호출 0.889s. relay hop(~1.5s)이 통째로 대기였고, 이건 로직이 아니라 스케줄 간격 때문.

## 핵심 결정
- **wake 는 outbox producer(EnqueueService)가 afterCommit 이벤트로 발행** — scheduler 결합 없음,
  다른 producer 생겨도 wake 누락 없음, dispatch 시그니처 불변.
- **outbox 유지, signal 은 힌트**. 진실은 outbox row, wake 는 빨리 깨우는 힌트 — 유실돼도 sweep 복구.
  정합성/at-least-once 불변.
- **in-process Semaphore(coalescing)**, pg NOTIFY 아님 — 단일 인스턴스. 다중 인스턴스 가면 NOTIFY 후보.
- **sweeper = relay 루프 await timeout 에 통합**(별도 잡 X). 재시도 row(backoff 복귀, scheduled_at 미래)는
  wake 밖이라 이 sweep 이 픽업.
- relay 루프는 **빌 때까지 drain**(한 batch 만 하면 backlog 나머지가 다음 timeout 까지 밀림) + yield 가드
  (producer 폭주 시 무한 점유 방지).

## 건드는 곳
RelayWaker · OutboxEnqueuedEvent + RelayWakeListener(AFTER_COMMIT) · EnqueueService publishEvent 한 줄 ·
relayService.drainUntilEmpty(가드) · BusAlertRelayLoop(SmartLifecycle, 기존 @Scheduled relay 대체) ·
properties sweep rename.

## 결과(목표)
due→SENT p95 2.4s급 → <1s. BusAlertRelayLatencyIT 로 interval vs wakeup 분포 대조.
발송 정합성(중복 0/전량 SENT/고착 0)은 기존 보장 유지.

## 정직 경계
wake-up 이 없애는 건 dispatch→send hop(≤1 interval)만. 감지 지연(poll 간격 + 버스데이터
신선도 15-30s)은 불변 — "보장" 범위 = 적재→발송 구간 + at-least-once.

## 검증
`./gradlew test`(Testcontainers PG17). 지연 IT + 기존 알림 스위트 회귀. 기존 무관 실패
(BusAlertControllerTest 등 PR#58 슬라이스) 제외.
