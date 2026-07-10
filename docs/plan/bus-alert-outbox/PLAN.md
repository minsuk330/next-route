# 버스 알림 Outbox 점진 적용 — 실행 플랜 (L티어, 측정 주도)

> route 알림(PR #59, 별도)과 독립. 버스 경로를 "측정된 문제 → 최소 해결 → 재측정"으로 단계 적용.
> 각 단계 = 독립 PR(fresh main). 다중 발송 채널(토스 외 추가 예정)이 outbox 별도 테이블의 forward 근거.

## 증거 (main, 이미 작성됨 — baseline)

- `BusAlertConcurrentPollingIT`: read-then-write claim 의 race window 를 latch 로 넓혀 **동시 발송(중복)** 재현.
  현재는 characterization(중복 단정은 `BUS_ALERT_CONCURRENT_ASSERT_DUPLICATE` env 게이트) → CI green 유지.
- `BusAlertDispatcherLoadIT` + `docs/plan/bus-alert-polling-load-test/RESULTS.md`: 단일 worker 1초 폴링 처리량.
  외부 지연 0에서 avg 41ms 여유, Toss 지연 주입 시 1초 예산 초과(직렬 발송 병목) → 병렬화 근거.

## 결함 요약

1. **정합성**: `BusAlertStateService.claim` = findById→상태검사→markProcessing(비원자). 동시 worker/다중 인스턴스에서
   같은 PENDING 을 둘 다 claim → 중복 발송. (단일 인스턴스 @Scheduled 비중첩이 지금은 우연히 방어.)
2. **결합**: 발송 상태가 구독 테이블(`bus_arrival_alert`)에 혼재. 토스 호출이 claim 트랜잭션 인근 → dual-write.
3. **병목**: 단일 worker 직렬 발송 → 외부 지연에 처리량 종속.

## 단계 (각 독립 PR)

| Step | 브랜치 | 내용 | 정당화 근거 | 마이그레이션 |
|---|---|---|---|---|
| A | `feat/bus-alert-atomic-claim` | claim → 조건부 UPDATE(단일 승자) | concurrency IT red→green | 없음 |
| B | `feat/bus-alert-outbox` | 별도 outbox 테이블 + **단일 relay** + send 분리(polling 은 enqueue 만) | dual-write 제거·채널 확장 | V(신규) |
| C | `feat/bus-alert-relay-parallel` | relay claimBatch SKIP LOCKED + N worker 병렬화 | load IT 처리량 개선 수치 | 없음 |
| D | `chore/notification-scheduler-isolation` | 알림 전용 TaskScheduler pool 분리 | 스케줄러 경합 지연(풀3/스케줄러15) | 없음 |
| E | `feat/bus-alert-collector-cache-reuse` | 발송 조건 판단 시 캐시 read(직접 API 호출 제거) | busApiCalls 감소 수치 | 없음 |

**Step B/C 경계**: B = 단일 relay + 테이블 + send 분리(정합성·채널). C = relay 병렬화(처리량). 병렬화를 B에서 빼 경계를 분명히.

---

## Step A: atomic claim (이번 PR)

**목표**: 중복 발송 race 를 최소 변경으로 제거. outbox 테이블 없이 concurrency IT 를 green(단일 승자 단정)으로.

- `BusArrivalAlertRepository.claimForProcessing(id, now)`: 조건부 UPDATE
  ```
  update BusArrivalAlert a
  set status=PROCESSING, processingStartedAt=:now, lastAttemptAt=:now, attemptCount=attemptCount+1
  where a.id=:id and a.status=PENDING and a.deletedAt is null
  ```
  (@Modifying, 영향행수 반환. markProcessing 과 동일 필드 전이.)
- `BusAlertStateService.claim`: findById→markProcessing 제거, `return claimForProcessing(id, now) == 1;`
  Postgres 행 락으로 동시 UPDATE 중 단일 승자만 1 반환, 나머지 0(스킵). REQUIRES_NEW tx 유지.
- **검증 IT** `BusAlertAtomicClaimIT`(신규): 실 `BusAlertStateService`(비앰프) + N worker 동시 `dispatchDue` →
  tossCalls==1, SENT==1. 기존 `BusAlertConcurrentPollingIT`(앰프=구설계 문서화)는 유지.

**롤백**: 코드만(마이그레이션 없음). claim 시맨틱 불변(PENDING→PROCESSING 단일 승자), 회귀 없음.

## Step B: outbox 테이블 + 단일 relay (다음 PR, 요약)

**핵심 = polling worker 가 Toss 를 절대 호출하지 않음.** flag on 시:
```
BusAlertDispatchService.processAlert()
  → enqueue(outbox INSERT)
  → return                 // Toss 호출 없음. polling 이 외부 지연에서 완전히 분리됨
발송은 BusAlertOutboxRelayScheduler(단일 worker)가 outbox 를 읽어 수행.
```
이로써 polling loop 의 1초 예산이 Toss latency 와 무관해짐(load IT 로 before/after 확인).

- **테이블** `bus_alert_notification`: status PENDING/PROCESSING/SENT/FAILED/DEAD/CANCELED,
  `channel`(TOSS/FCM/SMS…), `event_type`(ARRIVAL 등), attempt/requeue/scheduled_at/payload.
- **dedup_key = `BUS_ARRIVAL:{alertId}:{eventType}:{channel}`** — 다중 채널/이벤트 시 채널별 row 필요하므로
  alertId 단독 불가(채널이 dedup 단위). 발송당 정확히 1 row 를 이 키가 보장.
- enqueue = 단일 SQL(CTE: 구독 claim UPDATE + INSERT … ON CONFLICT (dedup_key) DO NOTHING RETURNING) — 선확인/예외흡수 금지(codex).
  Step A 의 조건부 claim 이 이 CTE 의 UPDATE 절로 재사용됨.
- markSent = outbox SENT + (마지막 채널이면) 구독 SENT 원자, 부모 재검증(취소 경합 가드).
- flag `notification.bus-arrival.outbox-enabled` 기본 off. flag on 시 구독 reclaim 비활성(재시도 소유권 outbox 단독).
- 상세·함정은 route PR #59 PLAN(docs/plan/notification-outbox) 의 PR-2 절 + codex 반영 준수.

## Step C~E: 요약

- C: `bus_alert_notification` claimBatch SKIP LOCKED(route PR#59 미러) + relay 워커 N개. load IT 로 처리량 before/after.
- D: 알림 job(route relay/check/spawner, bus relay)을 전용 `notificationTaskScheduler`(pool 2~4, prefix `noti-sched-`)로
  분리 → 공용 스케줄러와 경합 제거가 핵심. 공용 풀 크기 조정은 별도 판단(격리가 목적이지 증설이 목적 아님).
- E: **발송 조건 판단 시점**(`BusAlertDispatchService.processGroup()`의 `getArrInfoByStop` 직접 호출)을
  실시간 캐시(RedisBusRealtimeCacheAdapter, PR#51) read 로 대체 → busApiCalls 감소. (발송 시점 아님.)

## 검증

- 단위+IT: `./gradlew test` (Testcontainers postgres:17). 각 step 의 IT 로 red→green/before→after.
- 기존 무관 실패(BusAlert/FavoriteControllerTest=PR#58 AdminProperties, GongApiAdapterTest)는 제외 확인.
