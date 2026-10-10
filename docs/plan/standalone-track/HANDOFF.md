# 단독 추적(#265) — 모바일 인계

경로 검색 없이 **노선·방향·승차·하차만 골라** 지하철·버스를 추적한다. 서버는 이걸 **구간 1개짜리 여정**(`kind=TRACK`)으로 만든다. 그래서 진행 보고·명령·`/active`·지하철 링·Live Activity·lease 같은 기존 여정 계약은 **전부 그대로** 쓴다. 이 문서는 새로 생긴 것만 적는다.

기준: issue #265 **결정 5차**(2026-10-07) — 단독 버스 추적은 **차량을 특정하지 않는다.** 버스 탑승 중 위치는 앱 GPS 를 노선 선에 투영해 네이티브 버스 엔진(M5/M6)이 잡는다. 앱 설계 = nextroute-mobile `docs/plan/home-ride-tracking/DESIGN.md`.

서버 PR: #287(kind, V59) · #290(조회·시작) · #293(지하철 열차 선택, V60). #296(버스 차량 시각 대조)은 결정 5차에 따라 revert.

> ⚠ **§5 를 먼저 읽을 것.** 지금 서버 계약(§1·§3)은 앱 설계(DESIGN.md)와 **식별자 공간·선택 시점이 다르다.** 맞추는 서버 작업이 남아 있다.

## 0. 플래그 (전부 기본 꺼짐)

| 플래그 | 여는 것 | 꺼져 있으면 |
|---|---|---|
| `journey.track.enabled` | §1 조회·시작 API | 404 |
| `journey.subway.selection.enabled` | §3 지하철 열차 후보·선택(**일반 여정 포함**) | 404 |

## 1. 선택 → 시작

시작은 **OTP GTFS id** 를 받는다(`1:BS_TAGO_SEB121000091`, `1:RS_ACC1_S-1-0222` 처럼 콜론이 들어 있다). id 는 두 갈래로 얻는다.

- **track 조회 API(기본)** — 아래 `track/**`(#313)로 근처 정류장·역, 노선·방향, 하차 후보, 노선 선을 고르고 응답의 `gtfsStopId`·`routeGtfsId`·`boardStopGtfsId` 를 그대로 시작에 넘긴다. 앱은 기기 계정(#328)으로 늘 토큰이 있어 이 경로를 쓴다. 토큰을 아직 못 받은 순간(오프라인 첫 실행)에만 아래 서울 화면 API 로 대신한다.
- **서울 화면 API** — 응답에 실린 GTFS id 를 넘긴다(#298 A안, PR gtfs-id-exposure):

| 앱이 고르는 곳 | 응답 칸 | 시작 요청 칸 |
|---|---|---|
| 근처 버스 정류장 `GET /api/nearby/bus-stops` | `gtfsStopId` | `boardStopId` |
| 정류장 노선 `GET /api/stops/{stopId}/routes` | `routeGtfsId` | `routeGtfsId` |
| 노선 정류장 `GET /api/routes/{routeId}/stops` | 정류장마다 `gtfsStopId`(+최상위 `routeGtfsId`) | `alightStopId` |
| 근처 지하철역 `GET /api/nearby/subway-stations` | `gtfsStopId` | `boardStopId` |
| 남은 역 `GET /api/subway/stations/{id}/stations-ahead` | 최상위 `boardGtfsStopId`, 역마다 `gtfsStopId`·`routeGtfsId` | `boardStopId`·`alightStopId`·**하차역의** `routeGtfsId` |

- 지하철은 남은 역 응답 하나로 세 값이 다 나온다(근처 역의 `gtfsStopId` 는 서버 기동 직후 잠깐 null 일 수 있다 — 그때는 남은 역의 `boardGtfsStopId` 를 쓴다).
- 위 표는 서울 화면 API 경로다(서울만 — 값이 null 이면 `[추적 시작]` 을 막는다).
- **경기·지방은 아래 track 조회 API 로 고른다(2026-10-10, #333).** 전국 GTFS 기준이고 실시간 id(#313)·형제 정류장 합치기(#327)·노선 선(#314)이 붙어 있다. 로그인 제거(기기 익명 계정, #328)로 비로그인 401 문제는 없다(#320). 앱 홈 "근처에서 타기"는 이 API 로 통일하는 것을 권장한다.
- 어느 경로든 세 값 중 하나라도 null 이면 `[추적 시작]` 을 막고 "이 노선은 아직 추적할 수 없어요".

```
GET  /api/journeys/track/stops/near?lat=&lon=&mode=BUS|SUBWAY
  → [{gtfsStopId, name, lat, lon, mode, distanceMeters, arrivalStopId, arsId,
      arrivalStationId, lineId, lineName}]
GET  /api/journeys/track/stops/{gtfsStopId}/patterns
  → [{routeGtfsId, mode, routeName, color, directionLabel, terminalName, patternCode,
      arrivalRouteId, boardStopGtfsId, arrivalStopId}]
GET  /api/journeys/track/routes/{routeGtfsId}/stops?from={gtfsStopId}&pattern={patternCode}
  → [{gtfsStopId, name, lat, lon, index}]
GET  /api/journeys/track/routes/{routeGtfsId}/shape?from={gtfsStopId}&to=&pattern=
  → {points: [{x, y}], quality: SHAPE_OK | STOP_LINE}
POST /api/journeys/track?replace=false
  {routeGtfsId, boardStopId, alightStopId, patternCode?}
  → 201 JourneySnapshot   (단계 = WAIT_* + RIDE_*, kind=TRACK)
```

- `replace` 는 쿼리 파라미터(일반 시작과 같다). `true` 면 진행 중인 여정(일반 여정 포함)을 접고 시작한다 — DESIGN.md 5절 "바꾸기"와 같은 일을 서버가 한 번에 한다.
- 응답 `kind=TRACK` → `ActiveJourneyBanner` 대신 `TrackingProgressCard`. 재탐색 UI 없음(서버도 만들지 않고, 제안 요청은 409 `REROUTE_NOT_ELIGIBLE`).
- 오류: 422 `TRACK_STOPS_INVALID`(그 노선이 승차 뒤 한 바퀴 안에 하차를 안 지남) · 422 `TRACK_SCHEDULE_UNAVAILABLE`(오늘 시간표 없음) · 503 `TRACK_PATTERN_UNAVAILABLE`(노선 원천 바쁨) · 409 `ACTIVE_JOURNEY_EXISTS`.

## 2. 진행

일반 여정과 같다 — 대기→탑승 전이(`CLIENT_LEG_TRANSITION_DETECTED`)·진행 보고(`remainingStops`)·heartbeat·종료. 추적 카드는 기존 `useJourney` 스냅샷으로 그린다(새 폴링 없음, DESIGN.md 4절).

- **버스:** 서버는 차량을 고르지 않는다. 위치·남은 정류장·`PREPARE`/`ALIGHT_NOW` 는 앱 엔진이 낸다. 교정은 `[노선 바꾸기]` = 추적 종료(`cancelActiveJourney`) 후 정류장 시트 다시 — 서버 API 없음. 스냅샷에 버스 전용 안내 칸은 없다.
- **지하철:** 기존 M7 링·`subwayGuidance`·링 push 그대로. `[다른 열차예요]` = 기존 `POST /api/journeys/{id}/subway/binding/reset`.

## 3. 지하철 열차 고르기 (지금 서버 — 이미 시작한 여정 안에서)

지금 단계가 `WAIT_SUBWAY` 이고 다음이 `RIDE_SUBWAY` 일 때만.

```
GET  /api/journeys/{journeyId}/subway/train-candidates
  → [{trainNo, destination, express, trainType, arrivalSeconds, observedAt}]   곧 오는 2대
POST /api/journeys/{journeyId}/subway/train-selection   {trainNo, routeRevision}
  → {status: CONFIRMED, version, legIndex}
```

- 고르면 곧바로 확정. 진행 링은 이동 시작 관측(`boarding-evidence`)이 온 뒤부터 돈다.
- 고를 수 있는 열차 = 지금 도착 목록 전체 + 이 역·방향을 10분 안에 떠난 열차(#277 응답의 `departed`, `source` 가 `POSITION`·`ARRIVAL` 어느 쪽이든 — #324).
- 오류: 409 `NOT_WAITING_FOR_SUBWAY` · 422 `TRAIN_NOT_IN_CANDIDATES`(도착 목록에도 10분 안 출발에도 없음 — 낡은 화면) · 409 `STALE_ROUTE_REVISION` · 409 `SUBWAY_BINDING_BUSY`(바로 재시도).
- 앱: 시작 직후 고른 열차를 보낸다. `SUBWAY_BINDING_BUSY` 면 한 번 재시도하고, 그 밖의 실패는 사용자에게 알리지 않는다(열차가 안 묶여도 서버가 자동으로 맞춘다).

## 4. Live Activity

- 지하철: 기존 링 push 계약 그대로.
- 버스: 앱 엔진 값으로 앱이 그린다(서버 push·버스 안내 칸 없음).

## 5. 앱 설계(DESIGN.md)와 어긋나는 것 — 이슈로 진행

| # | 어긋남 | 진행 |
|---|---|---|
| 1 | 시작이 OTP GTFS id 만 받는데 앱은 서울 역·정류장·노선 id 로 고른다 | **해결.** 기본은 #313 전국 `track/**` 조회, 서울 화면 API 는 #298 A안(§1 표). 시작 요청에 `patternCode` 는 싣지 않는다(시작이 노선·승하차로 다시 찾음) |
| 2 | 방금 떠난 열차(최대 3대)를 주는 곳이 없다 | **#299 해결(#301)**: `GET /api/subway/stations/{id}/arrivals` 방향마다 `departed`(최근 3대·10분, `trainNo`·`departedAt`·`currentStationName`). 원천은 M7 출발 로그라 `trainNo` 가 열차 선택과 같은 공간. 위치 원천 없는 노선(경의중앙·경춘·수인분당·경강·서해 등)도 도착 피드로 채운다(#324, `source: ARRIVAL`) |
| 3 | 열차를 시작할 때 고른다 | **해결.** 시작 직후 단계가 `WAIT_SUBWAY` 라 #277 역 도착 응답의 `trains`(곧 오는 열차)·`departed`(방금 떠난 열차, 10분 안) 어느 쪽 `trainNo` 든 바로 `train-selection` 에 보내면 된다. 떠난 열차는 서버가 같은 M7 출발 로그로 확인하고 그 출발의 운행일로 확정한다 |
| 4 | `[탔어요]` = 이미 탔다 | 시작(대기 단계) 직후 앱이 탑승 전이를 바로 보내면 지금 서버로 동작한다. 별도 작업 없음 |

~~§1 조회 3개는 A안이면 앱이 쓰지 않는다 — 정리 대상.~~ 취소(2026-10-10): #313·#314·#327 로 track 조회 API 가 전국(경기·지방) 선택의 주 경로가 됐다. 지우지 않는다.

## 6. 경기·지방 (#333, 2026-10-10)

| 대상 | 서버 동작 | 앱 |
|---|---|---|
| 경기·TAGO 버스 대기 | `/active` 의 대기 단계 ETA 가 GBIS·TAGO 실시간의 다음 버스로 찬다(전엔 늘 비어 있음). 경기는 분 단위라 `APPROXIMATE` | 그대로 표시 |
| 경기·TAGO 버스 도착 조회 | `Direction.arrivalRouteId`·`arrivalStopId` 로 `GET /api/arrivals/bus/{stopId}/{routeId}`. TAGO 는 도시코드를 아는 노선만 id 가 온다(모르면 null = 실시간 없음) | null 이면 실시간 영역 숨김 |
| 지방 지하철(부산·대구·대전·광주·인천 등) | 실시간 원천이 없다(#245). `boarding-evidence` 응답 `realtimeSupported=false`, `subwayGuidance` 는 오지 않는다(예전엔 "확인 중" 고착) | `realtimeSupported=false` 면 관측 전송을 멈추고 시간표·형상 기반 화면. 열차 선택 UI 숨김 |
| 하차 금지 정류장 | 하차 후보에서 빠진다(#329, TAGO·시외버스 공항리무진 등). `index` 는 빈 번호가 생길 수 있다 | 그대로 |
