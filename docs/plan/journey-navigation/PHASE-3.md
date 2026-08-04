# Phase 3 — PR 분해 인덱스

- 상위 계획: [PLAN.md](./PLAN.md) §7 Phase 3
- 요구사항: [REQUIREMENTS.md](./REQUIREMENTS.md)
- 확정 기술 결정: [TECHNICAL_DECISIONS.md](./TECHNICAL_DECISIONS.md)
- 범위 변경 이력: [SCOPE-CHANGES.md](./SCOPE-CHANGES.md) (SC-4·SC-5·SC-7·SC-8·SC-9·SC-10·SC-14)
- 작성일: 2026-08-04

이 문서는 **인덱스**다. 실행 상세는 각 PR 의 `PLAN.md` 에 있다. 여기에 DDL·시그니처를 중복 기재하지 않는다(두 곳이 어긋나는 것을 막기 위해).

Phase 1·2 는 머지됐다. Journey 시작·상태머신·command·상세조회·종료가 동작하고, 승하차 확인 명령은 SC-4 로 제거되어 `CLIENT_LEG_TRANSITION_DETECTED` 하나로 일반화됐다. Phase 3 은 **여기에 "지금 어디까지 왔고 언제 도착하는지"를 채우는 단계**다.

## 1. PR 목록

| 순서 | 슬러그 | 티어 | 마이그레이션 | 선행 |
|---|---|---|---|---|
| ① | `journey-navigation-geometry` | **M** | 없음 | — |
| ② | `journey-progress-api` | **M** | V38 `journey_leg.last_progress_id` | ① |
| ③ | `journey-current-leg-eta` | **M** | 없음 | ② |
| ④ | `journey-wait-leg-eta` | **M** | 없음 | ③ |
| ⑤ | `journey-transfer-risk` | **M** | 없음 | ④ |
| ⑥ | `journey-transfer-probability` | **L** | 없음 | ⑤ (후순위) |
| ⑦ | `journey-snapshot-eta-compose` | **M** | 없음 | ⑤ |

각 PR 은 fresh main 기준 독립 브랜치다. 앞 PR 을 main 에 머지한 뒤 다음 브랜치를 fresh main 에서 만든다.

⑥은 ⑤·⑦과 병렬로 진행해도 되고 Phase 3 통과 조건에서 빼도 된다. PLAN §6.3 이 "캘리브레이션 데이터가 부족하면 probability 를 `null` 로 두고 유효한 slack 상태는 유지한다"를 이미 허용한다.

### ① `journey-navigation-geometry`

`GET /api/journeys/{journeyId}/navigation-geometry?routeRevision=` 하나를 낸다. 앱의 지도 표시(SC-8)와 GPS projection 엔진이 둘 다 이 응답을 기다리므로 Phase 3 의 첫 PR 이다.

leg 종류별 geometry 소스가 다르다는 것이 이 PR 의 핵심 복잡도다.

| leg 종류 | 소스 |
|---|---|
| `RIDE_BUS` | `bus_route_segment_polyline` — section 별 points + `quality_status` |
| `RIDE_SUBWAY` | `routeSnapshot` 의 ODsay `polyline` |
| `WALK`·`TRANSFER_WALK` | `routeSnapshot` 의 `walkSteps` — 누락 시 아래 fallback |

**도보 geometry 누락 처리(A+B)** — `WalkSegmentEnricher` 는 검색 deadline 예산 안에서만 도는 best-effort 보강이라 `walkSteps == null` 인 경로가 정상적으로 존재한다. 그런 경로도 Journey 를 시작할 수 있으므로 두 단계로 막는다.

1. **A. Journey 시작 시 보강** — 누락된 도보 leg 만 TMAP 을 호출해 `routeSnapshot` 에 채운다. Journey 시작은 경로 검색보다 deadline 여유가 크고, `tmap_walk_cache` 히트면 외부 호출이 없다.
2. **B. 직선 fallback** — A 가 실패하면 출발→도착 직선 보간으로 segment 를 만들고 `quality` 를 `APPROXIMATE` 로 내린다. `bus_route_segment_polyline` 의 `REVIEW|FAILED` 를 응답에서 누락시키지 않고 품질과 함께 내리는 것과 같은 철학이다.

geometry 를 `routeSnapshot` 에서 읽는 덕분에 revision 이 고정된 동안 좌표가 변하지 않는다. `tmap_walk_cache` 가 만료돼 TMAP 좌표가 미세하게 달라져도 진행 중인 Journey 의 segment 거리 배분(SC-9)이 흔들리지 않는다.

### ② `journey-progress-api`

`PUT /api/journeys/{journeyId}/progress` 와 V38 `journey_leg.last_progress_id`.

SC-14 의 조건부 병합 4규칙이 이 PR 의 전부다.

- 현재 `lastProgressId` 와 같은 요청 → no-op, `evaluationVersion` 증가 없음
- 새 `progressId` + `observedAt` 이 현재보다 최신 → 갱신 + `evaluationVersion` 1회 증가
- 과거 `observedAt` → stale, 상태 불변
- 같은 `observedAt` + 다른 `progressId`·내용 → `409 PROGRESS_TIMESTAMP_CONFLICT`

`geometrySegmentIndex`·`sectionId` 의 소속 검증이 ①의 응답 계약에 의존하므로 ① 다음이다. progress 는 heartbeat 를 겸해 lease 를 연장하지만 heartbeat API 자체와 만료 worker 는 Phase 4 다.

### ③ `journey-current-leg-eta`

이동 leg 공통 `StaticGeometryProgressEtaCalculator` 와 `CurrentLegArrivalPredictor` port + `CLIENT_GEOMETRY_PROGRESS` adapter. PLAN §6.1 의 두 식을 그대로 구현한다.

저장소를 보지 않는 순수 계산이라 DB 없이 단위테스트할 수 있다. `JourneyStateMachine` 을 그렇게 만든 것과 같은 이유다 — 사람이 눈으로 따져야 하는 규칙이기 때문이다.

가장 틀리기 쉬운 규칙은 **GPS 가 끊겼을 때 ETA 를 뒤로 밀지 않는 것**이다. `serverNow + remainingDuration` 으로 다시 계산하면 사용자 화면의 남은 시간이 줄지 않고 제자리걸음한다. 마지막 `currentLegArrivalAt` 을 유지해 로컬 countdown 을 계속한다.

### ④ `journey-wait-leg-eta`

`WAIT_BUS` 는 기존 `SingleTransferPredictor` 의 REALTIME→MODEL fallback 을 재사용하고, `routeOptions` 의 모든 `routeId` 를 평가해 routeId 마다 차량 후보를 최소 2대 보존한다(`NextBusCandidatePredictor`). `WAIT_SUBWAY` 는 `SubwayRealtimeCachePort` 에서 `lineId + 승차역 + 방향 + 목적지` 후보를 도착 초 순으로 읽고, 매칭 실패·stale 이면 정적 대기시간을 만들지 않고 `UNKNOWN` 을 반환한다.

**이 PR 의 통과 기준에 반드시 박을 것** — 3절의 "동기 경로에 외부 호출 금지"를 참고한다.

### ⑤ `journey-transfer-risk`

`TransferReadinessCalculator`(하차 버퍼 30초 + TMAP 도보 시간 → `userReadyAt`)와 `TransferRiskPolicy`(`COMFORTABLE ≥ 300초` / `CATCHABLE 120~299` / `AT_RISK 0~119` / `MISSED < 0` / 필수 ETA 누락 시 `UNKNOWN`) + hysteresis + 데이터 품질 가드.

이전 상태로 되돌아가는 임계를 더 높게 잡아 flapping 을 막는다. 임계값은 설정으로 둔다.

### ⑥ `journey-transfer-probability`

`TransferProbabilityEstimator` 와 버전된 residual calibration 아티팩트. 초기에는 위험 방향 downgrade 에만 사용하고 낙관 방향 upgrade 에는 쓰지 않는다.

아티팩트 버저닝·배포 방식과 데이터 확보에 설계 대안 논쟁 여지가 있어 L 이다. 착수 전에 설계를 확정한다.

### ⑦ `journey-snapshot-eta-compose`

현재 leg·환승·전체 ETA 합성, next action 생성, snapshot 필드 노출(`alighting.source`·`quality`, `activeTransfer`). S1·S2·S3·S4·S6 타임라인 fixture 통합 테스트가 여기 붙는다.

## 2. 티어 판정 근거

CLAUDE.md 의 L 판별 기준 — ①diff 모양이 미리 안 그려짐 ②스키마·API·모듈경계 신설 ③PR 여러 개 필요 ④설계 대안 논쟁 여지.

| 슬러그 | 티어 | 근거 |
|---|---|---|
| journey-navigation-geometry | M | 읽기 전용 endpoint 1개. leg 별 소스 3종이지만 전부 기존 자산이고 diff 모양이 그려짐 |
| journey-progress-api | M | endpoint 1개 + 컬럼 1개. 병합 규칙이 SC-14 에 문장으로 확정돼 있음 |
| journey-current-leg-eta | M | 순수 계산기 + port/adapter 1쌍. 식이 PLAN §6.1 에 확정 |
| journey-wait-leg-eta | M | 기존 predictor 재사용 + 후보 2대 확장. 단일 관심사 |
| journey-transfer-risk | M | 임계·hysteresis 정책 1개. 값은 설정 |
| journey-transfer-probability | **L** | 캘리브레이션 아티팩트 버저닝·배포에 대안 논쟁(④) + 데이터 확보 불확실 |
| journey-snapshot-eta-compose | M | 앞 PR 결과 조립 + 응답 필드 추가 |

Phase 3 전체는 L 규모지만 실제 구현은 M 크기 PR 로 나눈다. 각 M PR 은 앞 단계 merge 와 통과 기준 확인 후 다음으로 넘어간다.

## 3. Phase 3 공통 함정

각 PR 의 PLAN.md 에도 적지만 착수 전 한 번 훑을 것.

- **동기 경로에 외부 호출을 넣지 않는다** ← 제일 중요하다. Phase 3 에는 evaluator scheduler 가 없다. `next_evaluation_at` 을 채우는 주체는 **Phase 4** 다. 그래서 Phase 3 의 ETA 계산 트리거는 progress `PUT` 과 snapshot `GET` 시점의 동기 계산뿐이다. ④의 실시간 버스·지하철 조회가 이 경로에 동기로 들어가면 외부 API 지연이 `snapshot p95 500ms`·`progress p95 300ms` SLO(SC-13)를 직접 깨뜨린다. **④는 Redis 캐시된 스냅샷만 읽고 cache miss 는 즉시 `UNKNOWN` 으로 반환한다.** 실제 외부 갱신은 Phase 4 evaluator 가 소유한다. 지금 이 규칙을 통과 기준에 박지 않으면 나중에 걷어내기 어려운 동기 호출이 남는다.
- **마이그레이션 번호** — V37 까지 사용 중이다. ②는 V38 을 쓴다.
- **`.gitignore` 파일별 whitelist** — `.gitignore:9` 가 migration 디렉터리를 통째로 제외한다. 새 SQL 마다 `!` 라인을 추가하지 않으면 VPS 배포 시 파일이 없다.
- **마이그레이션 실행 주체는 Flyway 하나다** — `spring.flyway.enabled: true` 라 앱이 뜰 때 스스로 적용한다. 손으로 SQL 을 넣으면 `flyway_schema_history` 에 기록이 없어 다음 부팅에서 재실행되다 실패한다.
- **현재 차량을 식별하지 않는다** — SC-4 다. 현재 버스 leg ETA 에 `vehicleId`·실시간 차량 위치를 쓰면 안 된다. 실시간 버스 API 는 **환승할 다음 버스**(④)와 재탐색에만 쓴다. `journey_leg.vehicle_id` 컬럼은 legacy 호환으로 남아 있을 뿐 채우지 않는다.
- **`displayLocation` 과 `confirmedProgress` 는 다른 것이다** — SC-5 다. 서버가 받는 progress 는 앱이 이미 확정한 `confirmedProgress` 요약이다. 서버가 GPS 튐을 다시 판정하지 않는다.
- **정밀 좌표를 저장하지 않는다** — O10·SC-13 이다. progress 는 leg 별 최신 요약만 남긴다. 이력·궤적을 쌓지 않는다.
- **`plannedDuration` 은 이동시간이다** — SC-9 다. `WAIT_BUS`·`WAIT_SUBWAY` 대기시간으로 쓰면 안 된다.

## 4. Phase 3 완료 조건

- [ ] PR 순차 머지(①②③④⑤⑦, ⑥은 선택)
- [ ] VPS `flyway_schema_history` 에 V38 성공 기록
- [ ] REQUIREMENTS 의 T1~T9·T11, E1~E7·E9, A1~A2·A4~A5, C4 통과
- [ ] S1·S2·S3·S4·S6 타임라인 fixture 통과
- [ ] 동일 `progressId` 재전송·과거 `observedAt` 요청이 `evaluationVersion` 을 두 번 바꾸지 않음
- [ ] 실시간 버스·지하철 캐시 miss 에서 snapshot·progress 응답이 SLO 안에 `UNKNOWN` 으로 수렴
- [ ] 기존 `/api/transfer/predict`·경로 검색 p95 회귀 없음

## 5. Phase 4 이후로 넘긴 것

- `next_evaluation_at` 배치 claim scheduler 와 평가 worker (Phase 4)
- heartbeat API·lease 만료 worker·지하철 보호 lease 전이 (Phase 4)
- 실시간 스냅샷을 실제로 갱신하는 주체와 Journey 간 조회 공유 (Phase 4)
- 하차 checkpoint·local notification·telemetry batch API (Phase 5)
- 부분 재탐색과 routeOption 우선 재평가 (Phase 6)
