# Phase 4 — PR 분해 인덱스

- 상위 계획: [PLAN.md](./PLAN.md) §7 Phase 4
- 요구사항: [REQUIREMENTS.md](./REQUIREMENTS.md)
- 확정 기술 결정: [TECHNICAL_DECISIONS.md](./TECHNICAL_DECISIONS.md)
- 범위 변경 이력: [SCOPE-CHANGES.md](./SCOPE-CHANGES.md) (SC-3·SC-7·SC-12·SC-13)
- 앞 단계: [PHASE-3.md](./PHASE-3.md)
- 작성일: 2026-08-04

이 문서는 **인덱스**다. 실행 상세는 각 PR 의 `PLAN.md` 에 있다. 여기에 DDL·시그니처를 중복 기재하지 않는다(두 곳이 어긋나는 것을 막기 위해).

## 0. Phase 4 가 해결하는 것

Phase 3 은 "물으면 계산해서 답한다"까지 만들었다. 문제는 **묻는 사람이 없으면 아무 일도 일어나지 않는다**는 것이다. 사용자가 화면을 보지 않는 동안 환승 위험이 올라가도 서버는 그것을 모른다. 알림을 보낼 주체도 없다.

Phase 4 는 **서버가 스스로 도는 시계**를 만든다. 두 개다.

1. **평가 시계** — 여정마다 "다음에 언제 다시 볼지"를 정해 두고, 그 시각이 된 여정만 골라 평가한다
2. **만료 시계** — 앱 소식이 끊긴 여정을 접는다

여기서 함께 갚는 빚이 하나 있다. Phase 3 의 대기 leg ETA 는 조회 요청 경로에서 실시간 API 를 직접 부른다(PR #103). 요청 경로 밖에서 도는 평가기가 생기면 그 호출을 이쪽으로 옮길 수 있고, `snapshot p95 500ms`(SC-13) 를 provider 지연이 직접 깨뜨리는 상태가 풀린다.

## 1. PR 목록

| 순서 | 슬러그 | 티어 | 마이그레이션 | 선행 |
|---|---|---|---|---|
| ① | `journey-heartbeat-api` | **M** | 없음 | — |
| ② | `journey-lease-expiry-worker` | **M** | 없음 | ① |
| ③ | `journey-subway-offline-protection` | **M** | 없음 | ② |
| ④ | `journey-due-evaluator-claim` | **L** | V39 인덱스 보강 | ② |
| ⑤ | `journey-evaluation-dedupe` | **M** | 없음 | ④ |
| ⑥ | `journey-adaptive-interval` | **M** | 없음 | ④ |
| ⑦ | `journey-evaluator-resilience` | **M** | 없음 | ④ |
| ⑧ | `journey-load-harness` | **L** | 없음 | ④⑤⑥⑦ |

각 PR 은 fresh main 기준 독립 브랜치다. 앞 PR 을 main 에 머지한 뒤 다음 브랜치를 fresh main 에서 만든다.

①②③ 은 lease 계열이고 ④~⑧ 은 평가기 계열이다. ② 이후로는 두 줄기가 갈라져 ③ 과 ④ 를 병행할 수 있다.

### ① `journey-heartbeat-api`

`POST /api/journeys/{journeyId}/heartbeat` 하나를 낸다.

progress API(PR #101)가 이미 lease 를 연장하지만, **진행도를 만들지 못하는 상황**이 있다. GPS 정확도가 낮거나 터널이거나 아직 첫 sample 을 못 받은 때다. 그때도 앱이 살아 있음을 알려야 하므로 최대 30초 간격의 heartbeat 를 따로 받는다(SC-7 5항).

- 인증된 소유자의 ACTIVE Journey 만 조건부 갱신한다. `commandVersion` 은 건드리지 않는다
- lease write 는 여정별 최대 60초에 한 번으로 합친다 — progress API 가 쓰는 `extendLeaseIfStale` 를 그대로 재사용한다
- 응답은 최신 snapshot 이다. 앱이 heartbeat 응답으로도 상태를 맞출 수 있어야 한다

### ② `journey-lease-expiry-worker`

`max(guidanceLeaseUntil + expirationGrace, subwayOfflineProtectionUntil)` 이 지난 ACTIVE 여정을 `EXPIRED/CLIENT_LEASE_EXPIRED` 로 접는다.

**grace 를 두는 이유**(SC-3 1항) — iOS·Android 의 정상적인 background callback 은 늦게 도착할 수 있다. lease 하나만으로 종료를 결정하면 정상 사용 중인 사용자의 여정이 끊긴다. 3분 lease 뒤 2분을 더 기다린다.

- 배치 `SKIP LOCKED` claim 으로 여러 인스턴스가 같은 여정을 두 번 접지 않게 한다. `BusAlertNotificationRepository` 의 claim 쿼리가 같은 모양이라 그 패턴을 따른다
- 만료와 동시에 `next_evaluation_at` 을 비우고 pending 알림을 취소한다. 접은 여정이 계속 평가 대상으로 남으면 죽은 여정에 외부 API 를 쓴다
- 이미 종료된 여정에는 아무 것도 하지 않는다(멱등)

### ③ `journey-subway-offline-protection`

`RIDE_SUBWAY` 진입 시 `subwayOfflineProtectionUntil` 을 설정하고, 벗어나면 지운다(SC-12).

지하철 터널에서는 GPS 와 background callback 이 5분 이상 끊긴다. 일반 lease 만 적용하면 **정상적으로 앱을 쓰는 사용자의 여정이 지하철 이동 중에 종료된다**.

- 보호 시각 = `min(현재 leg 예상 종료 + 5분, leg 시작 + 60분)`
- 예상 종료를 아직 모르면 `leg 시작 + ODsay plannedDuration` 을 쓴다
- progress 로 ETA 가 보정되면 60분 상한 안에서 갱신한다. **시간 경과만으로 무한 연장하지 않는다**
- leg 를 벗어나거나 여정이 끝나면 제거한다

칼럼은 V37 에 이미 있다. 설정값도 `JourneyNavigationProperties` 에 있다(`subwayProtectionBufferSeconds`·`subwayProtectionCapSeconds`).

### ④ `journey-due-evaluator-claim`

Phase 4 의 심장이다. `next_evaluation_at` 이 지난 여정을 배치로 claim 해 평가하고 다음 시각을 다시 심는다.

- scheduler(고를 사람)와 worker(계산할 사람)를 나눈다. 한 스레드가 둘을 하면 느린 provider 하나가 claim 까지 막는다
- claim 은 `SKIP LOCKED` + `LIMIT batchSize`. 중복 claim 0 이 통과 기준이다
- 평가 본체는 Phase 3 의 `JourneySnapshotEtaComposer`·`TransferRiskEvaluator` 를 재사용한다. **여기서 계산 규칙을 다시 만들지 않는다**
- 평가 결과로 `evaluationVersion` 을 올리고 `next_evaluation_at` 을 다시 심는다
- **대기 leg 실시간 조회를 이 경로로 옮긴다.** 조회 요청은 평가기가 남긴 최신 결과를 읽고, cache miss 면 `UNKNOWN` 으로 즉시 답한다. 이것이 앞서 말한 빚을 갚는 지점이다

L 인 이유 — 조회 요청과 평가기가 같은 상태를 동시에 만지는 경계, `next_evaluation_at` 을 심는 규칙, 실시간 호출 주체 이동이 한꺼번에 얽힌다. 착수 전에 설계를 확정한다.

V39 는 만료·due claim 두 쿼리의 실행계획을 보고 필요한 부분 인덱스만 추가한다. V35 의 `next_evaluation_at` 인덱스와 V37 의 `idx_journey_expiration` 로 충분하면 마이그레이션 없이 끝낸다.

### ⑤ `journey-evaluation-dedupe`

평가 tick 안에서 같은 `(stopId, routeId, directionKey, targetSeq)` 조회를 한 번으로 합친다.

**통과 기준이 이것 하나로 요약된다** — 외부 API 호출이 *여정 수*가 아니라 *unique 관심 노선·정류장 수*에 근접해야 한다. 같은 정류장에서 같은 버스를 기다리는 사용자 100명이 100번을 부르면 quota 가 먼저 죽는다.

- routeId 별 차량 후보를 최소 2대 보존한다(T9·R2). PHASE-3 ④ 에서 다음으로 넘긴 항목이 여기 붙는다
- 기존 `CachedSearchTimeBusAdapter` 의 single-flight 와 겹치지 않게 경계를 맞춘다

### ⑥ `journey-adaptive-interval`

다음 평가 시각을 상황에 따라 다르게 심는다.

- 환승 임박·`AT_RISK` → 짧게. 도보 중·환승이 멀면 → 길게
- 여정 하나가 계속 짧은 간격을 요구하지 못하게 상한을 둔다
- 상태가 변하지 않으면 간격을 늘린다(backoff)

### ⑦ `journey-evaluator-resilience`

평가기 전용 scheduler pool·deadline·bulkhead·feature flag.

- 평가기가 요청 처리 스레드·DB 커넥션을 잡아먹지 않게 pool 을 분리한다
- 기존 검색 복원력 자산(deadline·bulkhead·single-flight)을 재사용한다
- `journey.navigation.evaluator.enabled` 로 즉시 끌 수 있어야 한다. Phase 8 의 점진 배포가 이 스위치를 쓴다
- 실시간 데이터 누락·다음 버스 후보 소실은 grace 후 `UNKNOWN`. 여정을 실패시키지 않는다

### ⑧ `journey-load-harness`

10,000 ACTIVE 여정 실험 하네스와 before/after 보고서.

- 측정: 외부 API 호출 수 대비 unique 관심 수, 중복 claim 수, 평가 backlog, 평가 p95, DB pool 사용량
- **baseline 이 목표를 넘으면** Redis ZSET due index 를 추가하고 PostgreSQL 을 canonical fallback 으로 유지한다. 넘지 않으면 추가하지 않는다 — 먼저 재는 것이 순서다
- 결과 문서를 보존한다. `route-search-resilience` 의 `RESULTS.md` 와 같은 형식

## 2. 티어 판정 근거

CLAUDE.md 의 L 판별 기준 — ①diff 모양이 미리 안 그려짐 ②스키마·API·모듈경계 신설 ③PR 여러 개 필요 ④설계 대안 논쟁 여지.

| 슬러그 | 티어 | 근거 |
|---|---|---|
| journey-heartbeat-api | M | endpoint 1개. lease 갱신 로직은 progress API 것을 재사용 |
| journey-lease-expiry-worker | M | claim 쿼리 1개 + 전이 1개. 기존 outbox claim 패턴을 따름 |
| journey-subway-offline-protection | M | 계산식이 SC-12 에 확정. 칼럼·설정 이미 있음 |
| journey-due-evaluator-claim | **L** | 조회·평가 동시 접근 경계 + 심는 규칙 + 실시간 호출 주체 이동이 얽힘(①④) |
| journey-evaluation-dedupe | M | 그룹화 키가 SC-10 에 확정. 단일 관심사 |
| journey-adaptive-interval | M | 규칙 표 하나. 값은 설정 |
| journey-evaluator-resilience | M | 기존 복원력 자산 재사용 + flag |
| journey-load-harness | **L** | 결과에 따라 Redis 도입 여부가 갈림(④). 실험 설계 자체가 산출물 |

## 3. Phase 4 공통 함정

- **접은 여정을 평가 대상에서 빼라.** 만료 전이와 `next_evaluation_at` 비우기가 한 트랜잭션에 있어야 한다. 나누면 죽은 여정에 외부 API 를 쓰는 창이 생긴다.
- **claim 과 계산을 같은 트랜잭션에 넣지 마라.** Phase 3 에서 같은 실수를 한 번 했다(#105 에서 수정). 외부 API 가 느린 동안 DB 커넥션을 붙잡으면 커넥션 풀이 먼저 마른다. claim → 트랜잭션 밖 계산 → 짧은 트랜잭션 저장 순서다.
- **`@Transactional` 자기호출은 프록시를 지나지 않는다.** 저장을 별도 빈으로 뺀다. `TransferAssessmentWriter` 가 그 예다.
- **평가가 `commandVersion` 을 올리면 안 된다.** 그것은 낙관적 락의 기준이라, 서버 평가로 올리면 앱이 보낸 정상 명령이 충돌로 거절된다. 평가는 `evaluationVersion` 만 올린다.
- **`JourneySchemaIT` 는 마이그레이션 SQL 을 `@Sql` 로 명시 나열한다.** 새 마이그레이션마다 그 목록에도 추가해야 한다. V38 을 빠뜨려 한 번 깨졌다.
- **`.gitignore` 파일별 whitelist** — `.gitignore:9` 가 migration 디렉터리를 통째로 제외한다. 새 SQL 마다 `!` 라인을 추가하지 않으면 VPS 배포 시 파일이 없다.
- **마이그레이션 번호** — V38 까지 사용 중이다. Phase 4 는 V39 부터다.
- **먼저 재고 나중에 넣는다.** Redis ZSET due index 는 ⑧ 의 측정이 목표 초과를 보인 뒤에만 추가한다. PLAN 이 명시한 순서다.

## 4. Phase 4 완료 조건

- [ ] PR 순차 머지(①②③④⑤⑥⑦⑧)
- [ ] REQUIREMENTS 의 G1~G7, X4·X11·X12 통과
- [ ] 외부 API 호출이 여정 수가 아니라 unique 관심 노선·정류장 수에 근접
- [ ] 중복 claim 0, 평가 backlog·p95·DB pool 사용량 기록
- [ ] 일반 leg 에서 3분 lease + 2분 grace 미수신 후 `EXPIRED`, 평가·알림 중단, 재실행 비복원
- [ ] 정상 background callback 이 3분 뒤 도착해도 2분 grace 안이면 ACTIVE 유지
- [ ] `RIDE_SUBWAY` 에서 5분 이상 신호가 없어도 보호 시각 전에는 ACTIVE, 보호 시각과 일반 lease 가 모두 지나면 종료
- [ ] 대기 leg 실시간 조회가 조회 요청 경로에서 빠지고 `snapshot p95 500ms` 를 만족
- [ ] 10,000 ACTIVE 여정 부하 결과와 before/after 보고서 보존
- [ ] 기존 경로 검색·버스 알림·환승 예측 회귀 없음

## 5. Phase 5 이후로 넘긴 것

- 하차 checkpoint·local notification 과 telemetry batch API (Phase 5)
- FCM token·sender·outbox·deep link (Phase 5). SC-3 3항에 따라 Phase 3 계약이 고정되면 Phase 4 부하 최적화와 병행할 수 있다
- 부분 재탐색과 routeOption 우선 재평가 (Phase 6)
- 환승 성공 확률 캘리브레이션 (PHASE-3 ⑥, 데이터 확보 후)
