# Phase 4 — PR 분해 인덱스

- 상위 계획: [PLAN.md](./PLAN.md) §7 Phase 4
- 요구사항: [REQUIREMENTS.md](./REQUIREMENTS.md)
- 확정 기술 결정: [TECHNICAL_DECISIONS.md](./TECHNICAL_DECISIONS.md)
- 범위 변경 이력: [SCOPE-CHANGES.md](./SCOPE-CHANGES.md) (SC-3·SC-7·SC-12·SC-13)
- 앞 단계: [PHASE-3.md](./PHASE-3.md)
- 작성일: 2026-08-04

이 문서는 **어떤 PR 을 어떤 순서로 낼지 정리한 목차**다. 구현 상세는 각 PR 의 `PLAN.md` 에 쓴다. DDL 이나 메서드 시그니처를 여기에 다시 적지 않는다 — 두 문서가 서로 어긋나기 시작하면 어느 쪽이 맞는지 알 수 없게 된다.

## 0. Phase 4 가 해결하는 것

Phase 3 은 "앱이 물어보면 그때 계산해서 답한다"까지 만들었다. 문제는 **아무도 묻지 않으면 아무 일도 일어나지 않는다**는 것이다. 사용자가 화면을 보지 않는 동안 환승 위험이 올라가도 서버는 그것을 모른다. 알림을 보낼 주체도 없다.

Phase 4 는 **요청이 없어도 서버가 알아서 돌리는 주기 작업**을 만든다. 두 개다.

1. **주기 평가** — 여정마다 "다음에 언제 다시 확인할지"를 미리 적어 두고, 그 시각이 된 여정만 골라 다시 계산한다
2. **만료 처리** — 앱에서 연락이 끊긴 여정을 종료 상태로 정리한다

여기서 Phase 3 이 남긴 문제 하나를 같이 해결한다. 지금은 대기 leg 의 ETA 를 앱의 조회 요청을 처리하는 도중에 실시간 API 를 직접 불러서 만든다(PR #103). 요청과 무관하게 도는 평가기가 생기면 그 호출을 평가기 쪽으로 옮길 수 있다. 그러면 외부 provider 가 느려질 때 `snapshot p95 500ms`(SC-13) 목표가 곧바로 깨지는 구조에서 벗어난다.

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

각 PR 은 최신 main 에서 딴 독립 브랜치로 만든다. 앞 PR 을 main 에 머지한 다음, 갱신된 main 에서 다음 브랜치를 딴다(브랜치를 위에 쌓지 않는다).

①②③ 은 lease(앱 생존 신호) 계열이고 ④~⑧ 은 평가기 계열이다. ② 까지 끝나면 두 갈래가 독립이므로 ③ 과 ④ 를 동시에 진행할 수 있다.

### ① `journey-heartbeat-api`

`POST /api/journeys/{journeyId}/heartbeat` 엔드포인트 하나를 추가한다.

progress API(PR #101)가 이미 lease 를 연장하지만, **보낼 진행도 자체가 없는 상황**이 있다. GPS 정확도가 낮거나, 터널 안이거나, 아직 첫 위치 sample 을 못 받은 때다. 그럴 때도 앱이 살아 있다는 사실은 알려야 하므로, 최대 30초 간격의 heartbeat 를 따로 받는다(SC-7 5항).

- 인증된 소유자의 ACTIVE Journey 만 조건부로 갱신한다. `commandVersion` 은 건드리지 않는다
- lease 갱신 쓰기는 여정별로 최대 60초에 한 번만 일어나게 합친다 — progress API 가 쓰는 `extendLeaseIfStale` 를 그대로 재사용한다
- 응답으로 최신 snapshot 을 돌려준다. 앱이 heartbeat 응답만으로도 화면 상태를 맞출 수 있어야 한다

### ② `journey-lease-expiry-worker`

`max(guidanceLeaseUntil + expirationGrace, subwayOfflineProtectionUntil)` 이 지난 ACTIVE 여정을 `EXPIRED/CLIENT_LEASE_EXPIRED` 로 종료시킨다.

**여유 시간(grace)을 두는 이유**(SC-3 1항) — iOS·Android 는 정상 동작 중에도 background callback 이 늦게 도착할 수 있다. lease 만료만 보고 종료를 결정하면 잘 쓰고 있는 사용자의 여정이 끊긴다. 그래서 lease 3분이 지난 뒤 2분을 더 기다린다.

- 여러 건을 한 번에 `SKIP LOCKED` 로 claim 해서, 인스턴스가 여러 대여도 같은 여정을 두 번 종료하지 않게 한다. `BusAlertNotificationRepository` 의 claim 쿼리가 같은 구조이므로 그 패턴을 따른다
- 종료와 동시에 `next_evaluation_at` 을 비우고 대기 중인 알림을 취소한다. 종료된 여정이 평가 대상에 남아 있으면 이미 끝난 여정 때문에 외부 API 를 낭비한다
- 이미 종료된 여정에는 아무 것도 하지 않는다(같은 작업을 여러 번 돌려도 결과가 같아야 한다 — 멱등)

### ③ `journey-subway-offline-protection`

`RIDE_SUBWAY` 에 진입할 때 `subwayOfflineProtectionUntil` 을 설정하고, 그 구간을 벗어나면 지운다(SC-12).

지하철 터널에서는 GPS 와 background callback 이 5분 이상 끊긴다. 일반 lease 규칙만 적용하면 **앱을 정상적으로 쓰는 사용자의 여정이 지하철을 타는 동안 종료된다**.

- 보호 만료 시각 = `min(현재 leg 예상 종료 + 5분, leg 시작 + 60분)`
- 예상 종료 시각을 아직 모르면 `leg 시작 + ODsay plannedDuration` 을 쓴다
- progress 로 ETA 가 보정되면 60분 상한 안에서 보호 시각도 갱신한다. **시간이 흘렀다는 이유만으로 무한히 연장하지는 않는다**
- 해당 leg 를 벗어나거나 여정이 끝나면 값을 지운다

컬럼은 V37 에 이미 있다. 설정값도 `JourneyNavigationProperties` 에 있다(`subwayProtectionBufferSeconds`·`subwayProtectionCapSeconds`).

### ④ `journey-due-evaluator-claim`

Phase 4 의 핵심이다. `next_evaluation_at` 이 지난 여정을 한 번에 여러 건 claim 해서 평가하고, 다음 평가 시각을 다시 기록한다.

- 대상을 고르는 scheduler 와 실제로 계산하는 worker 를 분리한다. 한 스레드가 둘을 다 하면 느린 provider 하나 때문에 claim 자체가 멈춘다
- claim 은 `SKIP LOCKED` + `LIMIT batchSize`. 중복 claim 이 0 건이어야 통과다
- 평가 로직 자체는 Phase 3 의 `JourneySnapshotEtaComposer`·`TransferRiskEvaluator` 를 재사용한다. **여기서 계산 규칙을 새로 만들지 않는다**
- 평가가 끝나면 `evaluationVersion` 을 올리고 다음 `next_evaluation_at` 을 기록한다
- **대기 leg 의 실시간 조회를 이 경로로 옮긴다.** 앱의 조회 요청은 평가기가 마지막으로 남긴 결과만 읽고, 캐시에 없으면 기다리지 않고 `UNKNOWN` 으로 바로 답한다. 앞에서 말한 Phase 3 의 문제를 해결하는 지점이다

L 인 이유 — 조회 요청과 평가기가 같은 데이터를 동시에 건드리는 경계, 다음 평가 시각을 정하는 규칙, 실시간 API 를 누가 호출할지 옮기는 일이 한꺼번에 얽힌다. 착수 전에 설계를 확정한다.

V39 는 만료 쿼리와 due claim 쿼리의 실행계획(EXPLAIN)을 확인한 뒤 필요한 부분 인덱스만 추가한다. V35 의 `next_evaluation_at` 인덱스와 V37 의 `idx_journey_expiration` 만으로 충분하면 마이그레이션 없이 끝낸다.

### ⑤ `journey-evaluation-dedupe`

한 번의 평가 주기(tick) 안에서 `(stopId, routeId, directionKey, targetSeq)` 가 같은 조회는 한 번만 하도록 합친다.

**통과 기준은 한 문장이다** — 외부 API 호출 수가 *여정 수*가 아니라 *서로 다른 관심 노선·정류장 수*에 가까워야 한다. 같은 정류장에서 같은 버스를 기다리는 사용자 100명 때문에 100번을 호출하면 API quota 가 먼저 바닥난다.

- routeId 별로 차량 후보를 최소 2대 남긴다(T9·R2). PHASE-3 ④ 에서 뒤로 미뤄 둔 항목이 여기에 붙는다
- 기존 `CachedSearchTimeBusAdapter` 의 single-flight 와 역할이 겹치지 않게 경계를 정한다

### ⑥ `journey-adaptive-interval`

다음 평가 시각을 상황에 따라 다르게 정한다.

- 환승이 임박했거나 `AT_RISK` 면 간격을 짧게, 도보 중이거나 환승까지 아직 멀면 길게 잡는다
- 여정 하나가 계속 짧은 간격만 요구하지 못하도록 최소 간격 상한을 둔다
- 상태가 계속 그대로면 간격을 점점 늘린다(backoff)

### ⑦ `journey-evaluator-resilience`

평가기 전용 scheduler pool·deadline·bulkhead·기능 플래그를 붙인다.

- 평가기가 요청 처리 스레드와 DB 커넥션을 잠식하지 않게 pool 을 분리한다
- 기존 검색 복원력 자산(deadline·bulkhead·single-flight)을 그대로 재사용한다
- `journey.navigation.evaluator.enabled` 로 즉시 끌 수 있어야 한다. Phase 8 의 점진 배포가 이 스위치를 쓴다
- 실시간 데이터가 없거나 다음 버스 후보가 사라지면, 유예 시간을 준 뒤 `UNKNOWN` 으로 표시한다. 여정 자체를 실패로 만들지 않는다

### ⑧ `journey-load-harness`

ACTIVE 여정 10,000건을 만들어 돌리는 부하 실험 도구와 before/after 보고서를 만든다.

- 측정 항목: 서로 다른 관심 수 대비 외부 API 호출 수, 중복 claim 건수, 평가 적체(backlog), 평가 p95, DB pool 사용량
- **먼저 측정한 baseline 이 목표를 넘으면** Redis ZSET due index 를 추가하고 PostgreSQL 은 원본(canonical) fallback 으로 남긴다. 목표 안이면 추가하지 않는다 — 재보고 나서 결정하는 순서다
- 결과 문서를 남긴다. `route-search-resilience` 의 `RESULTS.md` 와 같은 형식

## 2. 티어 판정 근거

CLAUDE.md 의 L 판별 기준 — ①고칠 코드의 모양이 미리 그려지지 않음 ②스키마·API·모듈 경계를 새로 만듦 ③PR 이 여러 개 필요 ④설계 대안을 두고 다툴 여지가 있음.

| 슬러그 | 티어 | 근거 |
|---|---|---|
| journey-heartbeat-api | M | 엔드포인트 1개. lease 갱신 로직은 progress API 것을 그대로 쓴다 |
| journey-lease-expiry-worker | M | claim 쿼리 1개 + 상태 전이 1개. 기존 outbox claim 패턴을 따른다 |
| journey-subway-offline-protection | M | 계산식이 SC-12 에 이미 확정. 컬럼과 설정값도 이미 있다 |
| journey-due-evaluator-claim | **L** | 조회와 평가가 같은 데이터를 동시에 건드리는 경계, 다음 평가 시각 규칙, 실시간 API 호출 주체 이동이 한꺼번에 얽힌다(①④) |
| journey-evaluation-dedupe | M | 같은 조회로 합칠 기준 키가 SC-10 에 확정. 관심사가 하나뿐 |
| journey-adaptive-interval | M | 규칙 표 하나면 끝. 실제 값은 설정으로 뺀다 |
| journey-evaluator-resilience | M | 기존 복원력 자산 재사용 + 기능 플래그 |
| journey-load-harness | **L** | 측정 결과에 따라 Redis 도입 여부가 갈린다(④). 실험 설계 자체가 산출물 |

## 3. Phase 4 공통 함정

- **종료한 여정은 평가 대상에서 즉시 빼라.** 만료 상태 전이와 `next_evaluation_at` 비우기가 같은 트랜잭션 안에 있어야 한다. 둘로 나누면 이미 끝난 여정에 외부 API 를 쓰는 구간이 생긴다.
- **claim 과 계산을 같은 트랜잭션에 넣지 마라.** Phase 3 에서 이미 한 번 겪었다(#105 에서 수정). 외부 API 응답을 기다리는 동안 DB 커넥션을 붙잡고 있으면 커넥션 풀이 먼저 마른다. claim → 트랜잭션 밖에서 계산 → 짧은 트랜잭션으로 저장, 이 순서다.
- **`@Transactional` 은 같은 빈 안에서 호출하면 프록시를 거치지 않아 트랜잭션이 걸리지 않는다.** 저장 로직을 별도 빈으로 분리한다. `TransferAssessmentWriter` 가 그 예다.
- **평가가 `commandVersion` 을 올리면 안 된다.** 이 값은 낙관적 락의 기준이라, 서버 평가로 올려 버리면 앱이 보낸 정상 명령이 충돌로 거절된다. 평가는 `evaluationVersion` 만 올린다.
- **`JourneySchemaIT` 는 마이그레이션 SQL 을 `@Sql` 에 하나씩 직접 나열한다.** 마이그레이션을 추가할 때마다 그 목록에도 넣어야 한다. V38 을 빠뜨려서 한 번 깨졌다.
- **`.gitignore` 는 파일 단위 whitelist 방식이다** — `.gitignore:9` 가 migration 디렉터리를 통째로 제외한다. SQL 을 추가할 때마다 `!` 라인을 넣지 않으면 VPS 배포 시 그 파일이 없다.
- **마이그레이션 번호** — 지금 V38 까지 쓰고 있다. Phase 4 는 V39 부터 시작한다.
- **먼저 재고 나중에 넣는다.** Redis ZSET due index 는 ⑧ 의 측정에서 목표를 넘긴 것이 확인된 뒤에만 추가한다. PLAN 이 정한 순서다.

## 4. Phase 4 완료 조건

- [ ] PR 을 ①②③④⑤⑥⑦⑧ 순서대로 머지
- [ ] REQUIREMENTS 의 G1~G7, X4·X11·X12 통과
- [ ] 외부 API 호출 수가 여정 수가 아니라 서로 다른 관심 노선·정류장 수에 가까움
- [ ] 중복 claim 0 건, 평가 적체·p95·DB pool 사용량을 기록해 둠
- [ ] 일반 leg 에서 lease 3분 + 유예 2분 동안 신호가 없으면 `EXPIRED` 로 전이하고 평가·알림이 멈추며, 이후 앱이 다시 요청해도 되살아나지 않음
- [ ] background callback 이 3분 뒤에 도착해도 유예 2분 안이면 ACTIVE 유지
- [ ] `RIDE_SUBWAY` 에서 5분 이상 신호가 없어도 보호 만료 시각 전에는 ACTIVE, 보호 시각과 일반 lease 가 모두 지나면 종료
- [ ] 대기 leg 실시간 조회가 조회 요청 경로에서 빠지고 `snapshot p95 500ms` 를 만족
- [ ] ACTIVE 여정 10,000건 부하 결과와 before/after 보고서를 문서로 남김
- [ ] 기존 경로 검색·버스 알림·환승 예측에 회귀 없음

## 5. Phase 5 이후로 넘긴 것

- 하차 시점 체크포인트, 앱 내 로컬 알림, telemetry 일괄 전송 API (Phase 5)
- FCM 토큰 관리·발송기·outbox·딥링크 (Phase 5). SC-3 3항대로 Phase 3 의 API 계약이 고정되면 Phase 4 부하 최적화와 동시에 진행할 수 있다
- 경로 일부만 다시 탐색하기, routeOption 을 우선해 재평가하기 (Phase 6)
- 환승 성공 확률 보정(캘리브레이션) — PHASE-3 ⑥, 데이터가 쌓인 뒤
