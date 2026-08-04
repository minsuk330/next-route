# journey-lease-expiry-worker — 실행 계획 (M)

- 상위: [PHASE-4.md](../journey-navigation/PHASE-4.md) ②
- 근거: SC-3 1항(lease 3분 + 유예 2분), SC-12 4항(`max(lease+유예, 보호시각)`), REQUIREMENTS X11·X12
- 선행: ① `journey-heartbeat-api` (PR #108, 머지됨)
- 사람용 요약: [SUMMARY.md](./SUMMARY.md)
- 작성일: 2026-08-04

## 1. 접는 기준

```
접는다  ⇔  guidance_lease_until < (now - expirationGrace)
          AND (subway_offline_protection_until IS NULL
               OR subway_offline_protection_until < now)
```

SC-12 4항의 `max(guidanceLeaseUntil + expirationGrace, subwayOfflineProtectionUntil) < now` 와 같은 조건을 인덱스가 쓸 수 있는 모양으로 옮긴 것이다. `max(...)` 를 그대로 쓰면 칼럼에 연산이 붙어 `idx_journey_expiration` 을 못 쓴다.

전이 결과는 `status='EXPIRED'`, `end_reason='CLIENT_LEASE_EXPIRED'`, `ended_at=now`, `next_evaluation_at=NULL`, `subway_offline_protection_until=NULL`.

## 2. 구현

### 2.1 `JourneySessionRepository.claimExpiredBatch`

native SQL 하나. `WITH picked AS (SELECT … FOR UPDATE OF s SKIP LOCKED) UPDATE … FROM picked … RETURNING j.id`.

- `@Modifying` 을 붙이지 않는다 — `RETURNING` 이 결과를 돌려준다. `BusAlertNotificationRepository.claimBatchForProcessing` 과 같은 형태다
- 반드시 `@Transactional` 안에서 부른다
- `ORDER BY guidance_lease_until` — 오래 방치된 것부터. 밀렸을 때 어느 것이 먼저 접히는지 정해 둔다
- `updated_at` 은 건드리지 않는다. `extendLeaseIfStale`·`bumpEvaluationVersion` 과 같은 관례다(bulk 쿼리는 auditing 을 타지 않는다)
- `status='ACTIVE'` 조건이 멱등을 보장한다. 여러 번 돌려도 이미 접은 여정은 다시 잡히지 않는다

### 2.2 `JourneyExpiryProperties` (`journey.navigation.expiry`)

| 키 | 기본값 | 뜻 |
|---|---|---|
| `enabled` | `true` | 끄면 스케줄러 빈 자체가 생기지 않는다 |
| `interval-ms` | `30000` | tick 간격. lease 3분 + 유예 2분이라 30초 지연은 계약 안이다 |
| `batch-size` | `200` | 한 번에 고르는 수 |
| `max-sweeps-per-tick` | `5` | 배치가 꽉 차면 이어서 훑되, 한 tick 이 끝없이 돌지는 않게 한다 |

판정 기준(`guidanceLeaseSeconds`·`expirationGraceSeconds`)은 `JourneyNavigationProperties` 에 그대로 둔다.

### 2.3 `JourneyLeaseExpiryService`

- `sweep()` — 트랜잭션 밖. `expireBatch()` 를 최대 `maxSweepsPerTick` 번 부르고, 배치를 채우지 못하면 멈춘다
- `expireBatch()` — `@Transactional`, SQL 한 문장만. 접은 수를 `journey.lease.expired{reason=client_lease_expired}` 카운터에 더하고 건수를 info 로, 여정 번호는 debug 로 남긴다

`sweep()` 에 `@Transactional` 을 붙이지 않는 이유 — 한 tick 이 여러 배치를 도는 동안 트랜잭션 하나가 열려 있으면 잠금 보유 시간이 배치 수만큼 길어진다.

### 2.4 `JourneyLeaseExpiryScheduler`

`@Profile("worker")` + `@ConditionalOnProperty(prefix="journey.navigation.expiry", name="enabled", havingValue="true", matchIfMissing=true)` + `@Scheduled(fixedDelayString="${journey.navigation.expiry.interval-ms:30000}")`.

공용 스케줄러 풀(`taskScheduler`, size 3)을 쓴다. 만료는 DB 만 만지는 짧은 잡이고, 몇 분 밀려도 여정이 조금 더 오래 ACTIVE 로 남는 것뿐이다. 평가기 전용 풀은 ⑦ 의 몫이고, 그때 이 잡을 그쪽으로 옮길지 다시 판단한다.

예외는 삼킨다. 던지면 로그만 쌓이고, 만료는 다음 tick 에 다시 시도하면 되는 일이다.

## 3. 하지 않는 것

- 대기 중인 알림 취소 — 여정 알림 테이블이 아직 없다(Phase 5). 생기면 이 SQL 에 한 줄 붙인다
- `journey_event` 기록 — 그 표는 `eventId` 를 가진 앱 명령의 이력이다. 서버가 접은 사실은 `end_reason` 이 말한다
- Redis 기반 due index — ⑧ 의 측정 뒤에만(PLAN 이 정한 순서)
- 만료 알림(푸시) — Phase 5

## 4. 검증

`JourneyLeaseExpiryIT`(@SpringBootTest, 로컬 Postgres)

| 확인 | 준비 | 기대 |
|---|---|---|
| 접는다 | lease 종료 3분 전 | `EXPIRED`/`CLIENT_LEASE_EXPIRED`, `ended_at` 채움 |
| 평가 대상에서 뺀다 | 위 + `next_evaluation_at` 채움 | `next_evaluation_at` null |
| 유예 안에서 기다린다 | lease 종료 30초 전 | `ACTIVE` 유지 |
| 지하철 보호 중 기다린다 | lease 10분 전 + 보호 10분 뒤 | `ACTIVE` 유지 |
| 보호까지 지나면 접는다 | lease 70분 전 + 보호 5분 전 | `EXPIRED`, 보호 시각 null |
| 멱등 | 두 번 실행 | 두 번째는 잡지 않음, `ended_at` 불변 |
| 중복 claim 0 | 대상 6건, 스레드 2개 동시 | 두 결과가 겹치지 않고 합집합이 대상 전체 |

여정 여러 개가 필요한 시험은 사용자도 그만큼 만든다 — `uq_journey_active_per_user` 때문이다.

`JourneyLeaseExpirySchedulerTest` — tick 이 `sweep()` 을 부른다, 실패해도 예외가 밖으로 나가지 않는다.

## 5. 함정

- **claim 과 계산을 같은 트랜잭션에 넣지 마라.** 여기서는 계산이 없어 문제가 안 되지만, ④ 에서 평가가 붙을 때 이 패턴을 그대로 복사하면 커넥션 풀이 마른다. #105 에서 한 번 겪었다
- **인덱스를 쓰는 모양을 유지하라.** 조건을 `guidance_lease_until + interval < now` 로 고치면 조용히 전체 훑기가 된다
- **`ACTIVE` 조건을 빼지 마라.** 멱등이 거기서 나온다
- **api 프로필에서 돌게 하지 마라.** VPS 는 `prod,api` 와 `prod,worker` 두 컨테이너다
