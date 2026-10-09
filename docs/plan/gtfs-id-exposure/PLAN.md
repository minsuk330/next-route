# gtfs-id-exposure — PLAN (M 티어, 에이전트 실행용)

issue #298 **A안**(2026-10-07 사용자 결정): 앱이 이미 쓰는 역·정류장·노선 응답에 **OTP GTFS id 를 같이 싣는다.** 앱은 고른 값의 GTFS id 를 그대로 단독 추적 시작 `POST /api/journeys/track`(#290)에 넘긴다. 시작 API 계약은 바꾸지 않는다.

기준 main `1bff3bfc`. 브랜치 `feat/gtfs-id-exposure`. 관련: #265, #277, #283, #290, #299(떠난 열차 — 별건).

## 0. 범위

| 응답 | 더하는 칸 | 원천 |
|---|---|---|
| `GET /api/nearby/bus-stops` → `NearbyBusStopResult` | `gtfsStopId` | 규칙 |
| `GET /api/stops/{stopId}/routes` → `StopRouteResult` | `routeGtfsId` | 규칙 |
| `GET /api/routes/{routeId}/stops` → `RouteStopsResult.RouteStop` | `gtfsStopId` (+ 최상위 `routeGtfsId`) | 규칙 |
| `GET /api/nearby/subway-stations` → `NearbySubwayStationResult` | `gtfsStopId` | 패턴 정차 ↔ 역 마스터 대응(캐시만) |
| `GET /api/subway/stations/{id}/stations-ahead` → `SubwayStationsAheadResponse` | 최상위 `boardGtfsStopId`, 역마다 `gtfsStopId`·`routeGtfsId` | 같은 패턴 정차(이미 대응 중) |

- **서울만.** `bus_stop`·`bus_route` 표와 위 버스 API 는 서울 데이터다(경기·지방 정류장은 이 API 들에 나오지 않는다 — 모바일 DESIGN.md 3-1 "서울만, 경기·지방은 #267 후"와 같다). #298 의 경기·TAGO 질문 답 = 이 PR 범위 밖, 경기·지방 정류장이 앱 흐름에 들어올 때 같은 규칙(`1:BS_TAGO_GGB{9}`·`1:BS_TAGO_{접두사 id}`)으로 더한다.
- `patternCode` 는 싣지 않는다 — 시작이 노선·승하차로 패턴을 다시 찾는다(#290 `TrackPatternSelector.select`, 코드 없음 허용).
- 모르는 값은 `null`(지어내지 않는다). 앱은 `null` 이면 `[추적 시작]` 을 막고 안내한다.

## 1. 버스 — 규칙 변환

`application/route/mapping/OtpGtfsIds.java`(신규, 순수):
```java
public static String seoulBusStop(String stopId)    // 9자리 숫자 → "1:BS_TAGO_SEB" + stopId, 아니면 null
public static String seoulBusRoute(String routeId)  // 9자리 숫자 → "1:BR_TAGO_SEB" + routeId, 아니면 null
```
- 역방향 규칙의 근거: `OtpSeoulIdentityResolver` 의 `BUS_STOP`·`BUS_ROUTE` 패턴(`^\d+:BS_TAGO_SEB(\d{9})$`, `^\d+:BR_TAGO_SEB(\d{9})(?:_[UD])?$`). feed 접두사는 `1:`(실측 2026-10-07 전 정류장).
- **공항버스 `_U`/`_D` 접미사**: 서울 노선 id 로는 어느 쪽인지 모른다 → 규칙은 접미사 없이 내고, 시작 쪽에서 흡수한다(§3).
- 각 DTO record 끝에 칸 추가 + 생성 지점에서 채운다. 생성 지점은 `grep -rn "new NearbyBusStopResult(\|new StopRouteResult(\|new RouteStopsResult.RouteStop(\|new RouteStopsResult(" src/main` 로 찾는다(테스트 생성자 호출도 같이 고친다).

## 2. 지하철 — 패턴 정차 ↔ 역 대응 재사용(#283)

### 2-1. 패턴 정차에 gtfsId
- `infrastructure/adapter/out/api/otp/OtpSubwayRoutePatternAdapter.java:43` 쿼리 `stops{ name lat lon }` → `stops{ gtfsId name lat lon }`, `parsePatterns` 에서 읽는다.
- `application/subway/dto/RailPattern.Stop` 에 `String gtfsId` 추가(첫 칸 또는 끝 — 생성자 호출부 전부 수정). 기존 픽스처(`src/test/resources/otp/subway-patterns/*.json`)에는 gtfsId 가 없다 → 파서는 없으면 null. 커버리지 시험은 그대로 통과해야 한다.
- `StationsAheadCalculator.Stop`(:57)에 `String gtfsStopId` 추가, `SubwayStationsAheadService.resolve`(:113 부근)에서 `stop.gtfsId()` 를 싣는다. `Pattern`(:60) 은 이미 `routeGtfsId` 를 가진다.

### 2-2. stations-ahead 응답
- `SubwayStationsAheadResponse` 최상위에 `boardGtfsStopId`, `Station` 에 `gtfsStopId`·`routeGtfsId`.
- `Station.routeGtfsId` = 그 역이 나온 패턴의 `routeGtfsId`(갈래·여러 route 노선에서 역마다 다를 수 있다 — 시작은 승차·하차가 **같은 route** 여야 하므로 앱은 하차역의 `routeGtfsId` 를 시작에 쓴다). `StationsAheadCalculator.Ahead` 에 원천 패턴의 `routeGtfsId` 를 실어 나른다(계산기에서 Ahead 를 만들 때 패턴을 알고 있다 — 없으면 Ahead 에 칸 추가).
- `boardGtfsStopId` = 승차역과 대응된 패턴 정차의 gtfsId(첫 일치).

### 2-3. 근처 지하철역
- `application/subway/service/SubwayGtfsStopIndex.java`(신규): `Optional<String> gtfsStopId(String lineId, String stationId)`.
  - **캐시만 읽는다**: `SubwayLinePatternService` 에 "이미 적재된 패턴만" 주는 메서드(`cachedPatterns(lineId, direction)` → 없으면 빈 목록, 적재하지 않음)를 더하고, 그 노선의 두 방향 패턴 정차를 `SubwayStationsAheadService.resolve` 와 **같은 대응**(`SubwayPatternStationMatcher.match` + `stationIndex.candidates(lineId)`)으로 stationId→gtfsId 맵을 만든다. 노선별로 패턴 묶음 객체가 같으면 맵을 재사용(`resolvedPatterns` 와 같은 식별 비교).
  - 근처 조회 응답 지연에 OTP 적재(노선당 1~2.5초)를 넣지 않는다 — 워밍업(`SubwayLinePatternWarmup`, 기동 시 전 노선)이 채운 뒤부터 값이 나온다. 그 전엔 null.
  - 방향 이름은 워밍업과 같은 목록에서 가져온다(`SubwayLinePatternWarmup:47` 부근의 lineId×direction 순회를 공용 메서드로 빼서 둘 다 쓴다).
- `NearbySubwayStationResult` 끝에 `gtfsStopId`, 생성 지점(`grep -rn "new NearbySubwayStationResult(" src/main`)에서 인덱스로 채운다.

## 3. 시작 쪽 흡수(#290, 작게)

`TrackPatternSelector.select`·`branchFor` 의 노선 비교를 `routeMatches(requested, actual)` 로: 같거나, `actual` 이 `requested + "_U"`/`"_D"` 면 일치(공항버스). 승차 다음 한 바퀴 안에 하차가 있는 패턴만 고르므로 방향은 정류장 순서가 가른다.

## 4. 작업 순서

- [ ] 1. `OtpGtfsIds` + 단위 시험(9자리·비숫자·null)
- [ ] 2. 버스 DTO 3개 칸 추가·채우기 + 컨트롤러/서비스 시험 갱신
- [ ] 3. `RailPattern.Stop.gtfsId` + 어댑터 쿼리·파서 + 파서 시험(gtfsId 있음/없음)
- [ ] 4. `StationsAheadCalculator.Stop.gtfsStopId`·Ahead 의 route + 서비스 매핑 + `SubwayStationsAheadResponse` 칸 + 시험(2호선·갈래 노선에서 역별 routeGtfsId)
- [ ] 5. `SubwayLinePatternService.cachedPatterns` + `SubwayGtfsStopIndex` + 근처 지하철역 채우기 + 시험(캐시 없음 → null, 캐시 있음 → 값, OTP 호출 0)
- [ ] 6. `TrackPatternSelector` 접미사 흡수 + 시험
- [ ] 7. 라이브 프로브(운영 OTP 터널 `ssh -f -N -L 18080:127.0.0.1:18080 vps`, 읽기만): 강남(2호선) stations-ahead 의 gtfsStopId·routeGtfsId 로 `TrackPatternSelector.select` 가 패턴을 찾는지, 서울 버스 정류장 규칙 id 가 `stop(id)` 로 존재하는지 5건. 커밋 안 하는 시험 파일로, 끝나면 지움
- [ ] 8. 전체 `./gradlew test --continue`(일회용 postgis `st-pg`:55432·`st-redis`:56379), 노트, HANDOFF(`docs/plan/standalone-track/HANDOFF.md` §1·§5) 갱신, PR

## 5. 검증

- 단위: 규칙 변환, 파서 gtfsId, 계산기·서비스 역별 gtfsStopId/routeGtfsId, 인덱스 캐시-only, 셀렉터 접미사
- 계약: 각 컨트롤러 시험에 새 칸 JSON 경로 단언
- 라이브: §4-7
- 기존 무관 실패: `JourneyEvaluationIT`·`JourneyEvalReadPathIT`·`JourneyTransferAssessmentIT`(10), `RerouteRemainingPathOtpIT` 1, 간헐 `JourneyNotificationSchemaIT`

## 6. 배포

마이그레이션 없음. 응답에 칸만 더한다(기존 앱 무영향). 지하철 근처 역의 `gtfsStopId` 는 기동 후 워밍업이 끝나야 채워진다.
