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

## 1. 선택 → 시작 (지금 서버)

모든 id 는 **OTP GTFS id** 다(`1:BS_TAGO_SEB121000091`, `1:RS_ACC1_S-1-0222` 처럼 콜론이 들어 있다).

```
GET  /api/journeys/track/stops/near?lat=&lon=&mode=BUS|SUBWAY
  → [{gtfsStopId, name, lat, lon, mode}]
GET  /api/journeys/track/stops/{gtfsStopId}/patterns
  → [{routeGtfsId, mode, routeName, color, directionLabel, terminalName, patternCode}]
GET  /api/journeys/track/routes/{routeGtfsId}/stops?from={gtfsStopId}&pattern={patternCode}
  → [{gtfsStopId, name, lat, lon, index}]
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
- 오류: 409 `NOT_WAITING_FOR_SUBWAY` · 422 `TRAIN_NOT_IN_CANDIDATES`(낡은 화면) · 409 `STALE_ROUTE_REVISION` · 409 `SUBWAY_BINDING_BUSY`(바로 재시도).

## 4. Live Activity

- 지하철: 기존 링 push 계약 그대로.
- 버스: 앱 엔진 값으로 앱이 그린다(서버 push·버스 안내 칸 없음).

## 5. 앱 설계(DESIGN.md)와 어긋나는 것 — 이슈로 진행

| # | 어긋남 | 진행 |
|---|---|---|
| 1 | 시작이 OTP GTFS id 만 받는데 앱은 서울 역·정류장·노선 id 로 고른다(근처 역·정류장, #277 역 도착, #283 stations-ahead, 정류장 노선, 노선 정류장) | **#298 A안으로 결정(2026-10-07)**: 앱이 이미 쓰는 응답에 `gtfsStopId`·`routeGtfsId`(가능하면 `patternCode`)를 같이 싣는다. 앱은 고른 값의 GTFS id 를 그대로 §1 시작에 넘긴다. 경기·지방 노선(서울 id 없음)을 어떻게 잇는지도 #298 에서 정한다 |
| 2 | 방금 떠난 열차(최대 3대)를 주는 곳이 없다 | **#299**: #277 역 도착 응답의 방향마다 `departed` 를 싣는다 |
| 3 | 열차를 시작할 때 고른다 | 시작 직후 단계가 `WAIT_SUBWAY` 라 #277 이 준 **곧 오는 열차**의 `trainNo` 를 바로 `train-selection` 에 보내도 된다(같은 실시간 도착 원천). **방금 떠난 열차는 지금 422** — 선택 검증이 현재 도착 목록만 본다. #299 에서 같이 고친다 |
| 4 | `[탔어요]` = 이미 탔다 | 시작(대기 단계) 직후 앱이 탑승 전이를 바로 보내면 지금 서버로 동작한다. 별도 작업 없음 |

§1 조회 3개(`/api/journeys/track/stops/**`·`/routes/**`)는 A안이면 앱이 쓰지 않는다 — 정리 대상.
