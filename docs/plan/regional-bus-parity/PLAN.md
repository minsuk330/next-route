# 지역 버스(경기·TAGO) 여정 기능을 서울과 맞추기 (regional-bus-parity, L)

## 목표
경기(GBIS)·지방(TAGO) 버스라도 **데이터가 있으면** 서울 버스와 같은 여정 기능을 켠다(사용자 결정 2026-11-26).
첫 성과는 경기 환승에서도 **재탐색 제안·환승 놓침 푸시**가 실제로 나가는 것이다.

## 배경 (실측, 2026-11-26)
- #627 배포 뒤 경기·TAGO 대기 구간의 `start_stop_id` 는 실시간 도착 API id(경기 9자리, TAGO `BSB…`)로 저장된다.
- 테스트 여정 555(반송동 → 강남역, 센트럴파크 1551/720-3 묶음 → 한빛마을 M4403): 재탐색 보드가 남은 구간 검색을 4번 불렀고
  후보 20개가 **전부 `unresolved-route`** 로 버려졌다(`journey_reroute_candidate_filtered_total{reason="unresolved-route"} 20`).
  원인: `RemainingPathEtaComposer.unresolvedReason`(:141-153)이 후보 첫 대기에 **서울** `routeId` + `boardingSeq` 를 요구하고,
  `BusBoardingTimeEstimator.hasScope`(:88-92)도 같다. 경기 노선은 서울 `bus_route` 표로 안 풀려 둘 다 null.
- 한빛마을 환승은 `transfer_connection.status=UNKNOWN`, `user_ready_at=null`(탑승 전이라 정상일 수 있음 — PR-1 검증에서 탑승 뒤 값 확인).
- 데이터: `regional_bus_route_stop` 경기 노선 2,170개(149,094행), TAGO 19,152개(105개 도시), 11-01 적재.
  최근 30일 여정의 지역 대기 노선 후보 중 (sourceRouteId, 정류장)으로 순번을 찾은 비율: 경기 68/71, TAGO 3/9.
- 이미 있는 지역 부품:
  - `RegionalArrivalLookup`(journey/service): `providerOf(routeId, sourceRouteId, sourceStopId)`, `nextArrival`, `boardableAt(provider, routeId, stopId, anchorAt)`, `arrivalAt`.
    재탐색 전용 일일 한도(`reroute-gg-arrival` 5000, `reroute-tago-arrival` 5000), 15초 메모리 캐시.
  - 경기 어댑터 `GyeonggiBusArrivalAdapter` 는 staOrder 를 노선 정류장 표에서 직접 찾는다(같은 정류장이 두 번이면 실패).
  - `WaitBusArrivalPredictor.predictRegional`, `BusTransferTargetArrivalPredictor` 지역 분기, `UpcomingStopWindowResolver.scanRegional`, `RideTruncationResolver.resolveRegional`, `RegionalRideLocator`.
- 운영 플래그: `JOURNEY_REROUTE_BOARD_ENABLED=true`, `JOURNEY_REROUTE_ALERT_ENABLED=true`(api·worker).

## 범위 (PR 단위, 순서대로)

### PR-1 재탐색 후보·기준선의 지역 승차 시각 (첫 성과)
목표: 경기·TAGO 노선이 첫(또는 뒤) 대기인 후보도 목적지 도착 시각을 낸다 → 제안·푸시가 생긴다.

1. `application/journey/dto/BoardingQuery.java`: 칸 추가 `String sourceRouteId`. 기존 6-인자 생성자는 유지(위임, sourceRouteId=null) — 호출부가 많다.
   - 지역 여부는 `RegionalArrivalLookup.providerOf(routeId, sourceRouteId, stopId)` 로 정한다(새 칸 하나로 충분).
2. `BusBoardingTimeEstimator.estimate`(:45): `hasScope` 가 false 이고 `providerOf(...) != null` 이면 지역 분기.
   - `regionalArrivalLookup.boardableAt(provider, sourceRouteId, stopId, anchorAt)`.
   - 결과 → `BoardingEstimate(boardingAt, boardable=true, source=REALTIME, quality=APPROXIMATE, calculatedAt=now, modelVersion=null)`.
     quality 를 APPROXIMATE 로 두는 이유: 경기는 분 단위(predictMin)이고 다음 1~2대만 온다. 품질 집계가 서울 GOOD 과 섞이지 않게.
   - `Lookup.QUOTA` → `BoardingOutcome.ofBlocked()` + miss 지표 `regional_quota`. `BLOCKED` → `ofBlocked()`(`regional_blocked`). `NO_REALTIME` → `none()`(`regional_no_realtime`).
     지표 tag 는 고정 집합(`JourneyRerouteMetrics`/`recordBoardingMiss` 의 허용 목록에 추가).
   - 예산(budgetMs): 지역 조회는 캐시가 있고 동기라 예산 검사만(남은 예산 ≤0 이면 부르지 않음).
3. `RemainingPathEtaComposer.unresolvedReason`(:141): WAIT_BUS 이고 `providerOf(option.routeId, option.sourceRouteId, waitLeg.startStopId)` 가 있으면 routeId·seq 없이 통과.
   `query(...)`(:155) 에 `option.sourceRouteId()` 전달.
4. `RemainingPathCandidateProvider.storedSteps`(:417): 저장된 대기 단계 query 에 `option.getSourceRouteId()` 전달 → 기준선(지금 경로)도 지역 대기를 계산.
5. `RerouteAcceptTxService.estimateBoarding`(:232): query 에 sourceRouteId 전달(수락 직전 첫 승차 재확인).
6. 경기 분 단위 보정: `RegionalArrivalLookup.gyeonggi`(:160-173) 가 `predictSec1/2` 가 있으면 그것을, 없으면 `predictMin×60`.
   (경기 응답 DTO `GyeonggiBusArrival` 에 predictSec 칸이 있는지 먼저 확인. 없으면 이 항목은 빼고 노트에 남김.)

#### Opus 사전 리뷰 반영 (2026-11-26, PR-1 에 포함)
7. **후보 전용 지역 한도(H1)**: `reroute-gg-arrival`/`reroute-tago-arrival` 은 대기 ETA·환승 판정·하차 창·자르기가 이미 나눠 쓴다(오늘 154/5000).
   후보 계산이 다 쓰면 모든 경기 여정의 대기 화면·환승 판정이 다시 비게 된다. → `RegionalArrivalLookup` 에 버킷 인자를 받는 오버로드,
   후보 계산은 `reroute-gg-candidate`/`reroute-tago-candidate`(`JourneyRerouteProperties.Board` 에 `quotaGyeonggiCandidate=1500`, `quotaTagoCandidate=1500`).
   보드 1회차·사건 1회의 지역 후보 조회 상한 `regionalCandidateLookupsPerRun=4`(계획 하차 자리 우선, 넘으면 그 후보는 `regional-cap` 으로 제외 + 지표).
8. **몫은 실제 호출일 때만(H2)**: 경기는 staOrder(`RegionalTableGyeonggiStopOrder.orderOf`)가 비면 몫을 잡기 전에 BLOCKED. 조회 실패는 짧게 음성 캐시(15초, 같은 캐시 칸).
   메모리 캐시 오래된 칸 정리(넣을 때 만료 칸 제거). 주석 "워커 한 대" 고침(worker2 있음 — JVM 별 캐시라는 사실만 적음).
9. **실시간 창 밖 앵커는 부르지 않음(H3)**: 추정기 지역 분기에서 `interpreter.modeFor(anchorAt, now) == MODEL_ONLY` 면 조회 없이 `none()`(`BusTransferTargetArrivalPredictor:124-129` 와 같은 규칙).
10. **같은 경로 거르기(H4)**: `RemainingPathCandidateProvider.boardCurrentRouteIds`(:933-938)·`currentRouteIds`(:316-327)에 각 구간이 고른 노선의 `getSourceRouteId()` 도 넣는다. 후보 쪽 비교 값도 sourceRouteId 를 포함하는지 확인하고 맞춘다. 지금은 지역 후보가 `prepare` 에서 버려져 안 드러났지만 PR-1 뒤엔 "계획 그대로" 경로가 자리를 차지한다.
11. **6번(경기 초 단위)은 필수(M1)**: `GyeonggiBusArrival.predictSec1/2` 있음(어댑터 :265-266). 초 값이 있으면 quality GOOD(TAGO arrtime 초도 GOOD — 기존 예측기와 같음), 분 값만 있으면 APPROXIMATE.
12. **지역 분기 정류장 id 를 `startStopId` 로(M2, PR-2 에서 당김)**: `WaitBusArrivalPredictor:108`, `BusTransferTargetArrivalPredictor:131`, `RegionalRideLocator:51,63-64` 가 원천 id 를 쓴다 → 같은 구간이 다른 캐시 키로 두 번 깎이고, GTFS 원형이면 환승 판정이 UNKNOWN → 사건 기반 재탐색 문(`RerouteTriggerPolicy:100-103`)이 닫힌다. `startStopId` 우선, 없으면 원천.
13. **뒤 대기도 미리 검사(M4)**: `prepare` 에서 모든 대기를 외부 호출 없이 검사(서울 scope 또는 지역 provider). 하나라도 안 되면 `unresolved-route` — 앞 대기에서 몫을 쓰고 버리지 않게.
14. **시간 예산(M5)**: 수락 트랜잭션(`RerouteAcceptTxService.estimateBoarding`)에서는 지역 조회를 하지 않는다(지금처럼 fail-open, 잠금 잡고 최대 5초 대기 방지). 보드·사건 경로는 남은 예산이 1초 미만이면 지역 조회 생략.
15. 작은 것: enum 은 `CurrentLegArrival.Source.REALTIME_ARRIVAL`. miss 지표는 기존 예측기와 같은 `mode="regional_bus"` + `lookup.failureReason()`. BLOCKED 는 `none()`(QUOTA 만 blocked, 기존 규칙). `summaries:695` 노선 이름이 비면 sourceRouteId 대신 "-" 그대로 두되 routeName 우선 확인.
- 확인됨(리뷰): APPROXIMATE 로 후보를 버리는 문 없음, 서울 동작 불변(`providerOf` 가 routeId 있으면 null), 경기 노선 표의 서울 정류장 id 는 서울 번호를 그대로 씀(서울 번호 2,114개 중 1,509개가 `bus_stop` 에 같은 id).

시험(계약 하나당 1개 이상):
- `BusBoardingTimeEstimatorTest`: 경기 query → 후보 버킷으로 boardableAt 호출, 초 값 GOOD·분 값 APPROXIMATE; QUOTA → blocked; BLOCKED·NO_REALTIME → none; MODEL_ONLY 앵커 → 조회 없음; 서울 query 는 기존 경로; 모양 모르는 sourceRouteId(시외) → no_scope.
- `RegionalArrivalLookupTest`: staOrder 없으면 몫 안 잡음, 실패 음성 캐시, 버킷 분리, predictSec 우선.
- `RemainingPathCandidateProviderTest`: 경기 다음 대기 노선과 같은 후보는 same-as-current 로 걸러짐(보드·사건), 회차당 지역 조회 상한.
- `WaitBusArrivalPredictorTest`/`BusTransferTargetArrivalPredictorTest`: 지역 분기가 startStopId 를 씀.
- `RemainingPathEtaComposerTest`: 경기 첫 대기 후보가 `unresolved-route` 로 안 버려지고 query 에 sourceRouteId 가 실림.
- `RemainingPathCandidateProviderTest`: 저장된 경기 대기의 기준선 query 에 sourceRouteId.
- `BoardingChainEtaCalculatorTest`: 서울 → 경기 두 대기 체인이 끝까지 계산(뒤 대기가 `boarding-unresolved` 로 안 끝남).
- `RerouteAcceptIT` 하나: 경기 첫 대기 후보 수락 시 재확인이 지역 조회를 탄다(포트 mock).

검증(운영): 경기 환승 여정에서 `journey_reroute_candidate_filtered_total{reason="unresolved-route"}` 증가가 멈추고, `reroute_proposal.candidates.slots` 가 채워지는지.
푸시는 기존 개선폭·간격 규칙 그대로(새 규칙 없음). 지역 한도 소비: `bus_api_quota` 의 `reroute-gg-arrival` 일일 사용량.

위험:
- 지역 한도 5000/일 소비 증가. 보드 갱신마다 후보 수 × 대기 수만큼 조회. 15초 캐시가 같은 (정류장, 노선)을 나눠 쓴다. 다 쓰면 blocked 로 후보가 빠질 뿐(서울 경로 영향 없음).
- 경기 예측은 다음 1~2대만 → 그 뒤 버스가 필요한 후보는 `no-prediction` 으로 빠진다(가짜 개선 없음, 보수적).

### PR-2 여정 시작 때 지역 노선 순번 풀기 + 지역 분기 정류장 id 정리
목표: 지역 구간도 "노선 + 정류장 순번"을 가진다 → 재탐색 원점 seq, 탑승 위치, 회차 노선 staOrder 겹침 해결의 바탕.

1. V79 `journey_leg_route_option`: `source_provider varchar(8)`(GG/TAGO, CHECK), `source_boarding_seq integer`, `source_alighting_seq integer`. 기존 `boarding_seq`/`alighting_seq` 는 서울 순번 의미 그대로(앱 노출 중, `JourneyQueryService:130-131`).
2. `JourneyTransitIdEnricher.resolveBusOption`(:100-112) `route.isEmpty()` 분기: `providerOf` 로 provider, `RegionalBusRouteStopStore.findRouteStops(provider, sourceRouteId)` 에서 승차 정류장 첫 등장 순번, 하차는 승차 이후 첫 등장(`RegionalRideLocator:63-64` 규칙). 겹치면(두 번 이상) null — 가짜값 금지.
   - `JourneyRouteOptionDraft`·`JourneyLegRouteOption`·`JourneyStartTxService`/`RerouteAcceptTxService` 복사 경로에 칸 추가.
3. ~~지역 분기 정류장 id 정리~~ PR-1 12번으로 당김(리뷰 M2).
4. `GyeonggiBusArrivalPort.getArrival` 에 staOrder 인자(있으면 표 재조회 생략, 회차 노선 겹침 해결).
5. `RerouteOriginResolver`: 지역 대기면 provider + source seq 로 원점 기록(`RerouteOrigin.provider` 선례).

시험: enricher(경기·TAGO 순번, 회차 겹침 null), 복사 경로 IT(JourneyStartIT), 지역 분기 정류장 id, 원점 resolver.

### PR-3 탑승 중 하차 창·자르기의 지역 보강
1. `UpcomingStopWindowResolver`: 사건 기반 경로도 `scanRegional`(지금은 보드 플래그 켜진 경로만, :95-102).
2. 경기 `vehId1/2` 로 "내 차" 이어 잡기(TAGO 는 차량 id 없음 — 순번만).
3. `RideTruncationResolver.resolveRegional`: PR-2 의 provider·seq 사용.

### 이번 범위 밖 (데이터 없음 또는 별건 결정)
- ML 도착 예측·버스 위치 기반 기능: **경기·TAGO 차량 위치 API 가 코드·자료에 없음**. 데이터가 생기면 별건.
- 사건 기반 재평가 감시자(`InterestIndex`/`BusRouteWatcher`): 지역 노선 전체 조회 API 와 몫 설계가 필요(계획 문서도 v1 제외). 별건.
- 여정 밖 기능: 버스 도착 알림(`BusAlertService`), 정기 경로 알림, 노선 정류장·폴리라인 API, `/api/transfer/predict`, 검색 운행종료 숨김(경기 `flag` 로 가능). PR-1~3 뒤 사용자 결정.
- 시외버스(ACC3): 실시간 원천 없음. 시간표 출발 시각 표시는 별건.

## 작업 순서
- [ ] PR-1 (브랜치 `feat/regional-bus-parity`): 1 → 2 → 3·4·5 → 6, 단계마다 단위 시험, 끝에 관련 IT + 리뷰 에이전트 → 배포 → 경기 환승 실여정 확인
- [ ] PR-2: V79 → enricher → 복사 경로 → 지역 분기 id → staOrder → 원점
- [ ] PR-3

## 검증 명령
- 단위: `./gradlew test --tests '*BusBoardingTimeEstimator*' --tests '*RemainingPathEtaComposer*' --tests '*RemainingPathCandidateProvider*' --tests '*BoardingChainEtaCalculator*' --tests '*RegionalArrivalLookup*'`
- IT(st-pg :55432, st-redis :56379, env api·db·auth·oauth, fcm.env 제외): `--tests '*RerouteAcceptIT' --tests '*JourneyEvaluationIT' --tests '*JourneyStartIT'`
- 운영: 위 지표 + `reroute_proposal` + 지역 한도 사용량.
