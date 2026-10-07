# #313 track 조회 응답에 실시간 조회 id 추가 (M 티어, slug: track-realtime-ids)

## Context
앱 홈 "근처에서 타기"를 전국 GTFS 기반 `GET /api/journeys/track/stops/near` 로 바꾸려 한다. 지금 쓰는 `/api/nearby/*` 는 서울 버스 DB·수도권 역 마스터만 봐서 경기·지방에서 비거나 일부만 나온다. 그런데 `track/*` 응답에는 GTFS id 만 있어서 앱의 실시간 기능(버스 도착 `/api/arrivals/bus/{stopId}/{routeId}`, 지하철 역 도착 `/api/subway/stations/{stationId}/arrivals`)을 부를 수 없다. track 응답에 **실시간 체계 id** 를 함께 싣는다. 못 풀면 null(가짜값 금지).

## 실측·조사 근거 (2026-10-07)
- 버스 도착 API(`BusArrivalQueryService`)는 id 모양으로 원천을 고른다. 서울 9자리 = 서울 DB, 그 밖 9자리 = GBIS, `BSB…` 처럼 영문 3자 접두사 = TAGO(도시코드는 노선으로 `TagoCityCodeLookup`). → 정류장·노선마다 그 체계 id 만 주면 된다.
- 버스 id 규칙은 `OtpSeoulIdentityResolver` 정규식을 그대로 쓴다: `BS_TAGO_SEB<9>`(서울, DB 존재 확인) / `BS_TAGO_GGB<9>`(경기 GBIS) / `BS_TAGO_<XXX>…`(TAGO, 접두사 포함). 노선도 같다(`BR_…`, `_U/_D` 접미 제거, 경기 마을버스 `BR_3100_`).
- 지하철: OTP 는 **호선마다 정류장이 따로** 있다(`1:RS_ACC1_S-1-0222 강남(2호선)`, 운영 그래프). 호선은 `stop.routes.shortName` 으로 알 수 있다.
- 역 연결은 `OtpSeoulIdentityResolver.resolveSubway`(1~9호선만, 좌표 50m) 대신 #283 의 `SubwayPatternStationMatcher`(이름 우선 ≤5km, 개명 좌표 ≤150m, 19노선 전수 커버리지 테스트)를 쓴다. 노선 표는 `SubwayRoutePatternAssembler.LINE_IDS`(shortName 명시표, 인천2호선 오인 없음), 역 후보는 `SubwayLineStationIndex.candidates(lineId)`.

## 계약 (필드 추가만, 하위 호환)
`GET /api/journeys/track/stops/near` → `Stop`:
```jsonc
{ "gtfsStopId": "...", "name": "...", "lat": 0, "lon": 0, "mode": "BUS|SUBWAY|RAIL",
  "distanceMeters": 120,
  // 버스
  "arrivalStopId": "121000123",   // 서울 9자리 / GBIS 9자리 / TAGO 접두사 포함. 못 풀면 null
  "arsId": "23123",               // 서울만(bus_stop.ars_id), 없으면 null
  // 지하철
  "arrivalStationId": "1002000222", "lineId": "1002", "lineName": "2호선" }
```
- 한 OTP 정류장을 지나는 지하철 호선이 둘 이상이면 호선마다 한 줄씩 준다(`/api/nearby/subway-stations` 와 같은 모양). 실측상 대부분 1개다.
- 호선을 모르거나 역 마스터에 없는 노선(지방 도시철도 등)이면 `arrivalStationId·lineId·lineName` 이 null 인 한 줄이다.

`GET /api/journeys/track/stops/{gtfsStopId}/patterns` → `Direction` 에 `arrivalRouteId`(버스 실시간 노선 id, 못 풀면 null)를 추가한다. 지하철은 null 이다(역 도착 API 가 역 id 만 받음).

`supportsPrediction`(선택)은 이번 범위 밖이다.

## 구현
- `application/route/port/out/TransitPatternPort.java`: `stopsNear` 반환을 `List<NearStop>` 으로 바꾼다. `record NearStop(TransitStop stop, Integer distanceMeters, List<String> routeShortNames)`.
- `infrastructure/adapter/out/api/otp/OtpTransitPatternAdapter.java` + `otp/trackPatterns.graphql` StopsNear: `stop { … routes { shortName } }` 와 `distance` 를 읽는다.
- 신규 `application/journey/service/TrackRealtimeIdResolver.java`: GTFS → 실시간 id. 순수 규칙 + 의존 `BusStopRepository`(서울 존재·arsId), `SubwayLineStationIndex`, `TagoCityCodeLookup`.
  - `busStop(gtfsStopId)` → `(arrivalStopId, arsId)`
  - `busRoute(gtfsRouteId)` → `arrivalRouteId`
  - `subway(stop, shortNames)` → `List<(stationId, lineId, lineName)>`
  - 버스 정규식은 `OtpSeoulIdentityResolver` 와 같은 것을 쓴다. 그 클래스에 정류장·노선 단위 공개 메서드를 추가해 재사용하고, 기존 메서드 동작은 그대로 둔다.
- `application/journey/dto/TrackViews.java`: `Stop`·`Direction` 필드 추가.
- `application/journey/service/TrackSelectionQueryService.java`: 위 resolver 로 채운다. 지하철은 호선별로 펼친다.
- 테스트:
  - `TrackRealtimeIdResolverTest`: 서울(DB 있음/없음)·GBIS·마을버스 `BR_3100_`·TAGO·`_U/_D`·인천2호선 제외·호선 2개 펼치기·연결 실패 null
  - `OtpTransitPatternAdapterTest`: StopsNear 파싱(routes·distance)
  - `TrackSelectionQueryServiceTest` 갱신
  - `JourneyTrackControllerTest` JSON 필드
- 문서: `docs/plan/track-realtime-ids/{PLAN,SUMMARY}.md`, HANDOFF(모바일: 필드 대응표, null 처리, track 은 로그인 필요·플래그 `JOURNEY_TRACK_ENABLED`), `implementation_note.md`.

## 주의 (앱 전환 전에 사용자에게 알릴 것)
- `track/*` 는 로그인 필요(익명 401)이고, 운영 플래그 `JOURNEY_TRACK_ENABLED` 가 꺼져 있다(꺼지면 404). 홈 근처 목록을 이 API 로 바꾸려면 플래그를 켜야 한다. 익명 사용자 처리는 앱·정책 결정이다.
- OTP 를 부른다(근처 조회 1회 + 정류장 패턴). 지금 `/api/nearby/*` 는 DB 만 본다.

## 검증
- 위 테스트 + journey·route·subway 패키지 회귀.
- 운영 OTP 읽기 전용 질의로 StopsNear 에 `routes{shortName}` 이 오는지 확인(강남역 좌표: 버스 서울 정류장, 2호선·신분당선 각 1줄).

## 배포
마이그레이션 없음. api 이미지 교체(플래그는 그대로 off — 켜는 건 별도 결정).
