# 버스 묶음 처리: 노선별 시간과 고른 버스로 여정 시작 — PLAN (티어 L)

이슈: #598(서버), #599(묶음 기준), minsuk330/jette-app#127, #123. 총괄 #603 의 1순위.

## 배경
- 검색은 같은 승하차 정류장의 버스를 한 경로로 묶어 `subPaths[].lanes[]` 에 최대 6개를 싣는다(#579, #586, `BusLaneMerger`).
- 구간 값(`sectionTime`, `stations`, `polyline`)은 대표 노선(`lanes[0]`) 것 하나뿐이다.
- 그 결과 두 군데가 틀린다.
  1. **환승 대기**: `TransferArrivalEnricher.processWave` 는 가장 먼저 오는 lane 을 탄다고 보면서(`updatePrevWave`), 탑승시간은 `prevBusSp.sectionTime()`(대표)로 더한다. 실측(운영 OTP, 묶인 노선 125개): 대표와 75% 가 ±1분, 11% 가 5분 초과, 최대 9.3분.
  2. **여정**: `JourneyStartTxService.saveRouteOptions` 가 `saved.get(0)` 을 무조건 선택한다. 시작 요청(`StartJourneyRequest{searchSessionId, pathKey}`)에 노선을 고르는 값이 없다. 사용자가 묶인 다른 버스를 타면 정거장 띠, 차량 위치, 하차 예측이 대표 기준이다.
- 사용자 결정(2026-11-16): 내비는 사용자가 어떤 노선을 타는지 명확히 알아야 한다. **앱은 버스 탑승 시점에 탄 노선을 선택하게 한다**(처음엔 "경로를 고를 때"였으나 같은 날 "탑승 시"로 확정). 묶음 허용 기준 변경은 환승 대기와 얽혀 있어 보류. 계약 추가(`lanes[].rideSeconds`) 승인, PR-1 부터 진행.

## 결론
1. lane 마다 탑승시간을 싣는다(`LaneResult.rideSeconds`, nullable, 추가만 하는 계약 변경).
2. 환승 대기 계산은 기준 lane 의 탑승시간을 쓴다(없으면 지금처럼 구간 값).
3. 탑승 시점에 탄 노선을 받는다(탑승 이벤트). 그 전까지는 지금처럼 첫 lane 이 선택 노선이다. ~~여정 시작 요청에 구간별 선택~~ 은 사용자 결정으로 탑승 시점 선택으로 바뀌었다 — 아래 PR-2 의 `busSelections` 는 탑승 이벤트의 노선 선택으로 다시 설계한다(PR-2 착수 때 계약 확정).
4. 고른 lane 기준으로 단계를 만든다: 탑승 소요 = 그 lane 의 `rideSeconds`, 정류장 목록과 선 모양 = OTP 에서 그 노선의 승차~하차 구간을 다시 받는다(실패하면 대표 값 + 표시).
5. 묶음 허용 기준(1.3배 + 5분)은 이 플랜에서 바꾸지 않는다. PR-1 뒤 실제 오차가 사라지는지 보고 #599 에서 정한다.

## 계약 (사람이 볼 것)
### 검색 응답 (PR-1)
`subPaths[].lanes[]` 항목에 칸 추가:
- `rideSeconds: Integer|null` — 그 노선으로 이 구간(같은 승하차 정류장)을 타는 정적 시간(초). 대표 lane 은 구간 `sectionTime` 과 같은 출처. 모르면 null(ODsay, 옛 스냅샷).
- 기존 칸과 순서는 그대로. `lanes[0]` = 대표.

### 여정 시작 (PR-2)
`POST /api/journeys` 요청에 칸 추가:
```json
{ "searchSessionId": "...", "pathKey": "...",
  "busSelections": [ { "subPathIndex": 1, "laneIndex": 2 } ] }
```
- `busSelections` 생략 또는 빈 배열 = 지금 동작(첫 lane).
- `subPathIndex` 가 버스 구간이 아니거나 `laneIndex` 가 범위 밖이면 400(`INVALID_BUS_SELECTION`). 같은 구간이 두 번 오면 400.
- 응답(`JourneyLegView`)은 그대로: `selectedRouteOptionId`, `routeOptions[]`. `JourneyRouteOptionView` 에 `rideSeconds` 추가(nullable).

### 상태 전이
- 여정 시작 시 선택 = 요청값. 재탐색 수락(`RerouteAcceptTxService`)은 지금 규칙 그대로.
- 여정 중 노선 바꾸기(고른 버스를 놓치고 묶인 다른 버스를 탐)는 PR-3 — 앱 흐름(jette-app#127) 확정 뒤 범위를 정한다.

## 작업 순서
### PR-1 lane 별 탑승시간 + 환승 대기 (서버, 단독 배포 가능)
- [ ] `application/route/dto/LaneResult.java`: `Integer rideSeconds` 추가(끝 칸). 생성 호출부(`LegacyRouteResponseMapper` 2곳, `OdSayApiAdapter.toLaneResult`, 테스트) 갱신. ODsay = null.
- [ ] `LegacyRouteResponseMapper` 버스 분기: 대표 = `leg.rideDurationSeconds()`, 대체 = `AlternativeRoute.rideSeconds()`.
- [ ] `BusLaneMerger`: A 재료(같은 지문 후보)에서 만든 `AlternativeRoute` 는 이미 boardAt/alightAt 을 싣는다 — 확인만. 시각이 없으면 null 그대로.
- [ ] `TransferArrivalEnricher.processWave`: `prev.basisLaneIndex` 의 lane 에 `rideSeconds` 가 있으면 그것으로, 없으면 `prevBusSp.sectionTime()*60`. `travelSeconds` 는 구간 값 유지(지하철, 도보).
- [ ] `RouteSearchTravelTimeEstimator.chooseBusLane`: 고른 lane 의 `rideSeconds` 를 탑승시간으로.
- [ ] 스냅샷 직렬화(검색 스냅샷에 `LaneResult` 가 저장된다): 옛 스냅샷 역직렬화에서 새 칸이 null 로 읽히는지 테스트.
- 테스트: 매퍼(lane 별 값), 환승 대기(먼저 오는 lane 이 대표가 아닐 때 닿는 시각이 그 lane 기준), null 폴백, 스냅샷 호환.
- 검증: `./gradlew test --tests '*LegacyRouteResponseMapper*' --tests '*TransferArrivalEnricher*' --tests '*RouteSearchTravelTime*' --tests '*RouteSearchService*' --tests '*Snapshot*'` + 운영 OTP 로 버스→버스 환승 OD 몇 개의 `estimatedUserArrivalAt` 전후 비교.

### PR-2 탑승 때 고른 노선으로 추적 (서버, 이슈 #598)
사용자 결정(2026-11-16): 앱은 **버스 탑승 시점**에 탄 노선을 고른다. 묶음은 서는 정류장이 완전히 같은 노선만이라(#605) 고른 노선에 따라 바뀌는 것은 노선 번호, 실시간 도착, 차량 추적 대상뿐이다. 정류장 목록, 선, 구간 구조는 그대로다 → **개정본을 새로 만들지 않고 지금 개정본에서 선택만 바꾼다.**

#### 현재 구조 (origin/main)
- 여정 시작: `JourneyStartTxService.saveRouteOptions` 가 lane 마다 `journey_leg_route_option` 행을 저장하고 `saved.get(0)` 을 선택(`JourneyLeg.selectRouteOption(optionId, routeId)`, 대기와 탑승 단계 둘 다).
- 조회 응답 `JourneyLegView{selectedRouteOptionId, routeOptions[]}`, `JourneyRouteOptionView{optionId, laneIndex, routeId, sourceRouteId, routeName, boardingSeq, alightingSeq}` — 앱은 이미 후보 전부를 받는다.
- 선택을 바꾸는 코드: `RerouteAcceptTxService.select(legs, waitLeg, option)`(재탐색 수락 때만).
- 같은 종류의 기존 API: 지하철 열차 선택 `POST /api/journeys/{journeyId}/subway/train-selection {trainNo, routeRevision, legIndex}`(`JourneySubwayBindingController`, `SubwayTrainSelectionService`). 낡은 개정본이면 `StaleRouteRevisionException`.

#### 계약 (구현본, Opus 검토 반영)
`POST /api/journeys/{journeyId}/bus/route-selection`
```json
{ "routeOptionId": 4812, "routeRevision": 3, "legIndex": 4 }
```
- `routeOptionId`: 조회 응답 `legs[].routeOptions[].optionId` 중 하나(필수).
- `routeRevision`: 앱이 보고 있는 개정본(필수). 다르면 409 `STALE_ROUTE_REVISION`(details = 현재 개정본).
- `legIndex`: 대상 버스 **탑승**(RIDE_BUS) 구간의 순번(지하철 열차 선택과 같은 규칙). 생략하면 지금 단계 기준 — 탑승 중이면 그 구간, 버스를 기다리는 중이면 바로 다음 구간.
- 응답 200: `JourneySnapshot`.
- 오류: 404 `JOURNEY_NOT_FOUND`, 409 `JOURNEY_NOT_ACTIVE`, 422 `BUS_ROUTE_SELECTION_NOT_ALLOWED`(버스 탑승 구간이 아님, 이미 내린 구간), 422 `BUS_ROUTE_OPTION_NOT_FOUND`(그 구간의 후보가 아님).
- 같은 후보 재전송: 200, 변화 없음.

#### 상태 전이 (구현본)
- 허용: 대상 탑승 단계가 PENDING 또는 ACTIVE(기다리는 중, 탑승 중). COMPLETED 등은 거절.
- 바뀌는 것: 대기 + 짝 탑승 단계의 `selectedRouteOptionId`, `routeId`. 비우는 것: 대기 단계의 `predictedEndAt`·`evidenceSource`·`evidenceObservedAt`, 탑승 단계의 `predictedEndAt`.
- **안 건드리는 것**: 탑승 단계의 진행도·`remainingStops`·근거(앱 관측, 정류장이 같아 유효. 비우면 탑승 위치 판정이 "진행 없음"), `vehicleId`(단독 추적 워커 소유, V61 CHECK 가 종류·출처와 묶음), 알림과 환승 판정 행(다음 평가가 수 초 안에 다시 쓴다).
- **`commandVersion` 은 올리지 않는다**: 앱이 선택과 탑승 전이를 연달아 보내므로, 올리면 전이가 409 로 거절된다. `evaluationVersion` +1 과 `JourneyVersionChangedEvent` 로 조회 갱신을 알린다.
- 재평가: `journey.scheduleEvaluationAt(now)`(급한 줄). 돌고 있던 평가는 저장 때 소유 검사(`next_evaluation_at = 선점값`)에 걸려 버려진다. `ReevaluationTrigger` 는 쓰지 않는다(진행 중 평가가 옛 노선 값을 다시 쓴다). 재탐색 보드도 `rerouteRefreshSignal.requestNow`.
- 잠금: `findByIdAndUserIdForUpdate`(여정 행). 재탐색 수락, 구간 전이와 같은 잠금.
- 진행 보고와의 경합: `JourneyLeg` 에 `@DynamicUpdate` 추가(바뀐 칼럼만 UPDATE). 진행 보고는 여정 행을 안 잠그는 bulk UPDATE 라, 전 칼럼 flush 면 그 값을 덮는다.
- 재탐색 수락: 복제한 앞 구간은 사용자가 고른 후보를 `optionIndex` 로 이어받는다(전에는 항상 첫 후보로 되돌렸다). 새 구간은 첫 후보.
- 지도 좌표 버전 키: 첫 후보가 아닌 노선을 고르면 `:b{단계}.{후보}` 가 붙는다(색, 정류장 원천이 달라짐). 고르지 않은 여정은 키 불변.
- 감사: `journey_event` 행은 쓰지 않고 로그 한 줄(`[BusRouteSelection]`). 최종 선택은 단계의 `selectedRouteOptionId` 에 남는다.
- 마이그레이션 없음.

#### 구현 좌표
- `application/journey/service/BusRouteSelectionService`(단일 @Transactional), `infrastructure/.../JourneyBusRouteSelectionController`, `dto/BusRouteSelectionRequest`
- `JourneyLeg.reselectRouteOption`, `@DynamicUpdate`
- `RerouteAcceptTxService.carriedOption`
- `JourneyNavigationGeometry.versionKey(4인자)`, `JourneyNavigationGeometryService.busSelectionToken`
- `JourneyErrorCode.BUS_ROUTE_OPTION_NOT_FOUND`, `BUS_ROUTE_SELECTION_NOT_ALLOWED`
- 테스트: `BusRouteSelectionIT`(9), `JourneyBusRouteSelectionControllerTest`(3), `RerouteAcceptIT.keepsUserSelectedBusRouteOnCopiedLegs`, `JourneyEvaluationIT.evaluatesWithUserSelectedRoute`

### 앱 (jette-app#127, #123)
- 탑승 시([탔어요] 또는 탑승 판정) 묶인 노선이 2개 이상이면 고르게 하고 `POST …/bus/route-selection` 을 보낸다. 탑승 전환 이벤트는 지금처럼 따로 보낸다(선택 전에 읽은 `commandVersion` 그대로 써도 된다). 200 을 받으면 지도 좌표를 다시 받는다(버전 키가 바뀐다).
- 조회 응답의 `routeOptions[]` 로 목록을 그리고, 응답 스냅샷으로 화면을 바꾼다.

## 리스크와 미결
- OTP 패턴 조회가 여정 시작 경로에 들어간다(지연, 실패). 예산과 폴백을 PR-2 에서 정한다. 대안: 검색 때 lane 별 정류장 목록까지 스냅샷에 싣기(응답 커짐).
- `rideSeconds` 는 시간표 값이다. 대표와 다른 편(다음 차)의 값이라 시각대에 따라 대표 편과 수십 초 다를 수 있다.
- 경기, TAGO 노선이 섞인 묶음: 대체 lane 의 실시간 id 는 대표와 같은 지역일 때만 있다(#579). 고른 lane 에 실시간 id 가 없으면 대기 화면 도착 정보가 없다 — 앱 표시 규칙 필요.
- 묶음 허용 기준(#599): PR-1 뒤 재평가.

## 배포
- PR-1: api, worker(알림 추정이 worker 에서 돈다). 마이그레이션 없음.
- PR-2: 마이그레이션 없음. api, worker, worker2 같은 이미지.
