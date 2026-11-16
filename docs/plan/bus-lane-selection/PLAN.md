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

#### 계약
`POST /api/journeys/{journeyId}/bus/route-selection`
```json
{ "routeOptionId": 4812, "routeRevision": 3, "legIndex": 2 }
```
- `routeOptionId`: 조회 응답 `routeOptions[].optionId` 중 하나(필수).
- `routeRevision`: 앱이 보고 있는 개정본(필수). 다르면 409(`StaleRouteRevisionException`, 지하철 선택과 같은 응답).
- `legIndex`: 대상 버스 구간(WAIT_BUS 또는 RIDE_BUS 의 순번). 생략하면 지금 단계 기준(대기 중이면 그 대기, 탑승 중이면 그 탑승).
- 응답 200: `JourneySnapshot`(`GET /api/journeys/{id}` 와 같은 모양). 앱은 받은 스냅샷으로 화면을 갈아끼운다.
- 오류: 남의 여정 404, 그 구간의 후보가 아닌 `routeOptionId` 422(`BUS_ROUTE_OPTION_NOT_FOUND`), 버스 구간이 아니거나 이미 끝난 구간 422(`BUS_ROUTE_SELECTION_NOT_ALLOWED`), 끝난 여정 422.
- 같은 후보를 다시 고르면 200, 변화 없음(재전송 안전).

#### 상태 전이
- 허용: 대상 대기 단계가 PLANNED·ACTIVE 이거나 짝 탑승 단계가 ACTIVE(탑승 중에 잘못 골랐으면 다시 고른다). 탑승 단계가 COMPLETED 면 거절.
- 바뀌는 것(대기 + 짝 탑승 단계 둘 다): `selectedRouteOptionId`, `routeId`. 옛 노선 기준 값은 비운다: `vehicleId`, `predictedEndAt`, `remainingStops`, `evidenceSource`·`evidenceObservedAt`.
- 안 바뀌는 것: 개정본 번호, 구간 수와 순번, 정류장, 선, `plannedDurationSec`(정류장이 같아 차이 최대 2분).
- 선택 뒤: 여정 `commandVersion` 을 올리고(앱 조회와 낙관적 잠금이 변화를 본다), 재평가 대상으로 표시해 다음 평가가 새 노선으로 도착과 환승을 다시 잰다.
- 감사: `journey_event` 에 한 줄(`BUS_ROUTE_SELECTED`, payload = 옛·새 optionId, 노선 이름).
- 동시성: 평가 워커와 같은 여정 행을 건드린다 — 이벤트 적용 경로(`ApplyJourneyEventUseCase`)가 쓰는 잠금과 버전 규칙을 그대로 따른다.

#### 작업
- [ ] `infrastructure/.../in/api/journey/JourneyBusRouteSelectionController`(또는 기존 컨트롤러에 메서드) + `BusRouteSelectionRequest`.
- [ ] `application/journey/service/BusRouteSelectionService`(읽기·검증) + `BusRouteSelectionTxService`(쓰기). 선택 교체는 `RerouteAcceptTxService.select` 의 규칙을 공용 메서드로 뽑아 재사용(대기와 짝 탑승이 같은 후보 행).
- [ ] `JourneyLeg.reselectRouteOption(optionId, routeId)`: 선택 교체 + 옛 노선 기준 값 비우기.
- [ ] `JourneyEventType.BUS_ROUTE_SELECTED` 추가, events API 는 여전히 `CLIENT_LEG_TRANSITION_DETECTED` 만 받는다(기존 검증 유지).
- [ ] 재평가 표시(기존 재평가 트리거 경로 재사용) — 구현 때 좌표 확인.
- [ ] 여정 시작 때 후보마다 `routeId`·`boardingSeq`·`alightingSeq` 가 채워지는지 확인(첫 후보만 채우는 곳이 있으면 고친다) — 고른 노선의 실시간 조회에 필요.
- 테스트(계약마다): 선택하면 대기·탑승 단계의 선택과 routeId 가 바뀐다 / 옛 차량·예측 값이 비워진다 / 같은 후보 재선택은 변화 없음 / 다른 구간의 후보 422 / 낡은 개정본 409 / 끝난 탑승 422 / 남의 여정 404 / commandVersion 증가 / 선택 뒤 평가가 새 routeId 로 도착을 묻는다(IT) / 재탐색 수락 뒤에는 새 개정본의 후보로만 고를 수 있다.
- 검증: `./gradlew test --tests '*BusRouteSelection*' --tests '*JourneyStart*' --tests '*RerouteAccept*' --tests '*JourneyEvaluation*'`(st-pg, st-redis).

### 앱 (jette-app#127, #123)
- 탑승 시([탔어요] 또는 탑승 판정) 묶인 노선이 2개 이상이면 고르게 하고 `POST …/bus/route-selection` 을 보낸다. 탑승 전환 이벤트는 지금처럼 따로 보낸다.
- 조회 응답의 `routeOptions[]` 로 목록을 그리고, 응답 스냅샷으로 화면을 바꾼다.

## 리스크와 미결
- OTP 패턴 조회가 여정 시작 경로에 들어간다(지연, 실패). 예산과 폴백을 PR-2 에서 정한다. 대안: 검색 때 lane 별 정류장 목록까지 스냅샷에 싣기(응답 커짐).
- `rideSeconds` 는 시간표 값이다. 대표와 다른 편(다음 차)의 값이라 시각대에 따라 대표 편과 수십 초 다를 수 있다.
- 경기, TAGO 노선이 섞인 묶음: 대체 lane 의 실시간 id 는 대표와 같은 지역일 때만 있다(#579). 고른 lane 에 실시간 id 가 없으면 대기 화면 도착 정보가 없다 — 앱 표시 규칙 필요.
- 묶음 허용 기준(#599): PR-1 뒤 재평가.

## 배포
- PR-1: api, worker(알림 추정이 worker 에서 돈다). 마이그레이션 없음.
- PR-2: 마이그레이션 없음(기존 표와 칸만 쓴다. `journey_event.type` 이 DB 제약이면 값 추가 마이그레이션 필요 — 구현 때 확인). api, worker, worker2 같은 이미지.
