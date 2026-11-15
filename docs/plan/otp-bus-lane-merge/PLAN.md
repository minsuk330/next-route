# OTP 버스 합치기 (A+B) — PLAN (에이전트용, 티어 M)

브랜치 `feat/otp-bus-lane-merge` (fresh origin/main). 이 파일과 SUMMARY.md 는 구현 PR 에 포함.

## 배경 (실측, 2026-11-16)
- 네이버, 카카오는 같은 정류장에서 타서 같은 정류장에 내리는 버스를 한 경로로 묶는다(카카오 강남→홍대: 당산역13번출구 `5714, 603, 761`).
- 우리는 `ItineraryRanker.fingerprint`(노선 이름 순서열)로 후보를 가르고, OTP 버스 구간 lane 은 항상 1개(`LegacyRouteResponseMapper` 버스 분기 `lanes = List.of(new LaneResult(...))`).
- OTP 엔진에는 "합치기" 필터가 없다. 재료는 `Leg.nextLegs(numberOfLegs)` (`OpenTripPlanner/.../routing/alternativelegs/AlternativeLegs.java`, GTFS API 는 `AlternativeLegsFilter.NO_FILTER` 고정 → 다른 노선 포함, 버스는 `originModesWithParentStation` 미지정이면 정확히 같은 정류장만).
- 실험(스크립트, 운영 OTP, 7 OD × 08:30/14:00): A = 비용 0, 서울역→잠실 10→6개. B(nextLegs 30) = OTP 평균 1.0s→1.2s, 서울역 5번승강장→명동입구 4→14개 노선. 노이즈 = `(예약)` 노선, 탑승시간이 훨씬 긴 노선(수원 `2-1`).

## 결정
1. A: 버스 구간 지문 = `BUS|<승차 stopId>><하차 stopId>`. stopId 없으면 기존처럼 `BUS|<노선이름>`. 지하철, 철도는 기존 규칙(노선 이름 + 급행 종류) 그대로.
2. B: 본 검색, 추가 검색, 일반 대안 되찾기 질의에 `nextLegs` 를 싣는다. 재탐색(`RemainingRouteSearchService`)은 싣지 않는다.
3. 대표 선택 규칙은 그대로(가장 이른 도착 → 비용 → 엔진 순서). 비용 우선은 늦은 출발편을 고름(2호선 08:48 편 cost 3317 < 08:31 편 3347).
4. lane 순서 = 대표 노선 → 나머지는 다음 출발(boardAt) 이른 순 → 이름. 상한 `maxBusLanes=6` (서울 실시간 조회가 lane 마다 순차, `TransferArrivalEnricher` lane 루프).
5. (구현 중 추가, 실측) 정류장 지문으로 줄 세운 뒤, 노선 이름 지문이 위 대표와 같은 대표는 뺀다 — 같은 노선이 정류장만 달리 두 번 보이는 것 방지(강남→홍대 740 강남역9번출구·12번출구, 사당→판교 1500-2).
6. 응답 계약 무변경. `SubPathResult.lanes` 는 원래 배열(ODsay 다중 노선). 구간 공통 필드(startLocalStationID, endLocalStationID, stations, polyline, sectionTime)는 대표 노선 값.

## B 거르기 규칙 (BusLaneMerger)
- 대체 후보 = (A) 같은 지문 그룹의 다른 후보들이 그 버스 구간에서 탄 노선 + (B) 대표 leg 의 nextLegs.
- 제외: 노선 이름이 대표와 같음, 이름에 `(예약)` 포함, 이름/ID 둘 다 null, 하차 stopId 가 대표 하차 stopId 와 다름(B만, 값 있을 때).
- 제외: 탑승시간(alightAt - boardAt) > 대표 탑승시간 × `alternativeRideFactor`(1.3) + `alternativeRideSlackSeconds`(300). A 후보도 같은 규칙 적용.
- 같은 이름은 하나(boardAt 가장 이른 것).
- 정렬 후 상한 `maxBusLanes - 1` 개.

## 작업 순서
- [ ] 1. 모델
  - 신규 `src/main/java/watoo/grd/nextroute/application/route/routing/AlternativeRoute.java`
    ```java
    public record AlternativeRoute(String routeId, String routeName, String routeColor,
                                   String alightStopId, Instant boardAt, Instant alightAt) {
        public Integer rideSeconds() { ... null-safe ... }
    }
    ```
  - `RoutingLeg.java`: 컴포넌트 `List<AlternativeRoute> alternatives` 를 맨 끝에 추가(compact ctor 에서 null → `List.of()`, `List.copyOf`). 기존 보조 생성자들은 빈 목록 전달. `withAlternatives(List<AlternativeRoute>)` 추가. 정식 생성자를 부르는 곳(`new RoutingLeg(` 전체 grep — main 의 `OtpPlanConnectionMapper.toLeg`, `OdsayRoutingAdapter`, 테스트 빌더들)을 맞춘다. 기존 `with*` 메서드들이 새 필드를 보존하는지 확인.
  - 검증: `./gradlew compileJava compileTestJava`
- [ ] 2. 쿼리와 매퍼
  - `src/main/resources/otp/planConnection.graphql`: 변수 `$withAlternatives: Boolean! = false`, `$alternativeLegs: Int! = 0` 추가. `legs { ... }` 안에
    `nextLegs(numberOfLegs: $alternativeLegs) @include(if: $withAlternatives) { mode start { scheduledTime } end { scheduledTime } to { stop { gtfsId } } route { gtfsId shortName color } }`
    (OTP 2.9 스키마 `nextLegs(numberOfLegs: Int!, ...)`: `tools/transit-routing-benchmark/otp-native/app/resources/org/opentripplanner/apis/gtfs/schema.graphqls` 확인됨.)
  - `OtpPlanConnectionMapper.toLeg`: `legNode.path("nextLegs")` 배열 중 mode=BUS 만 `AlternativeRoute` 로(시각은 기존 `start.scheduledTime` 파싱 함수 재사용). 거르기 없음(원재료).
  - fixture: `src/test/resources/otp/planconnection-bus-alternatives.json` (서울역 5번승강장→명동입구 버스 + 2호선, nextLegs 에 일반, 예약, 긴 탑승 노선 포함). 실측 응답에서 잘라 만든다.
  - `OtpPlanConnectionMapperTest`: nextLegs 파싱, 없으면 빈 목록.
- [ ] 3. 질의 플래그
  - `RoutingQuery.java`: 컴포넌트 `boolean alternativeLegs` 추가(기본 false), `withAlternativeLegs()` 추가. 기존 `withModes`, `withExcludedRouteIds` 가 값을 보존하도록.
  - `OtpRoutingProperties.java`(application/route/config): `busLaneMergeEnabled=true`, `alternativeLegs=30`, `maxBusLanes=6`, `alternativeRideFactor=1.3`, `alternativeRideSlackSeconds=300` + getter/setter, 주석에 실측 근거.
  - `OtpRoutingAdapter.requestBody`: `q.alternativeLegs() && properties.isBusLaneMergeEnabled() && properties.getAlternativeLegs() > 0` 이면 `vars.put("withAlternatives", true)`, `vars.put("alternativeLegs", n)`.
  - `RouteSearchService.runOtpSearch`: 본 `RoutingQuery` 생성 뒤 `.withAlternativeLegs()` (추가 검색, 되찾기는 이 query 에서 파생되므로 따라옴). `RemainingRouteSearchService` 무변경.
  - 테스트: `OtpRoutingAdapterHttpTest` 에 요청 본문 변수 on/off.
- [ ] 4. 지문과 합치기
  - `ItineraryRanker.fingerprint`: BUS leg 이고 `stops` 첫/끝 `sourceStopId` 둘 다 있으면 `BUS|from>to`. 클래스 주석 규칙 1 갱신.
  - 신규 `src/main/java/watoo/grd/nextroute/application/route/routing/BusLaneMerger.java` (final, static, 순수)
    ```java
    public static List<RoutingItinerary> merge(List<RoutingItinerary> representatives,
                                               List<RoutingItinerary> pool, Rules rules)
    public record Rules(int maxLanes, double rideFactor, int rideSlackSeconds) {}
    ```
    대표마다 `ItineraryRanker.fingerprint` 같은 풀 후보들을 모으고, 대표의 i번째 transit leg 가 BUS 면 그룹 다른 후보의 i번째 transit leg 를 `AlternativeRoute`(boardAt/alightAt/stop 포함)로 + 대표 leg 의 `alternatives`(nextLegs) → 규칙 적용 → `leg.withAlternatives(final)`; BUS 아닌 leg 는 `alternatives` 비움. itinerary 재구성은 기존 `RoutingItinerary` with/생성 방식 확인 후 사용.
  - `RouteSearchService.selectCandidates`: ranking 켜졌고 `busLaneMergeEnabled` 면 `BusLaneMerger.merge(chosen, its, rules)`. 꺼지면 대표 leg alternatives 를 비워서 반환(nextLegs 원재료가 lane 으로 새지 않게).
  - 테스트: `ItineraryRankerTest`(같은 정류장 다른 노선 → 1개, 같은 노선 다른 정류장 → 2개, stopId 없으면 이름), 신규 `BusLaneMergerTest`(예약 제외, 탑승시간 상한, 이름 중복, 대표 자신 제외, 정렬, 상한, 지하철 무변경, 하차 정류장 다른 B 제외).
- [ ] 5. 응답 lane
  - `LegacyRouteResponseMapper` 버스 분기: 지금 인라인 서울→경기→TAGO 해석을 `BusIds resolveBusIds(String boardStopId, String alightStopId, String routeId, String routeName)` (record: startStationId, endStationId, routeLocalId) 로 뽑는다. 대표는 지금과 같은 값. 대체 노선마다 같은 함수로 `routeLocalId` 만 쓴다(정류장 id 는 대표 값 유지).
  - lanes = 대표 `LaneResult` + 대체 노선마다 `new LaneResult(name, name, null, null, null, routeLocalId, displayColor(routeId, routeName, color))`. `displayColor(RoutingLeg)` 는 새 오버로드에 위임.
  - 테스트: `LegacyRouteResponseMapperTest` 대체 노선 2개 → lanes 3개, 서울 노선 busLocalBlID 채움, 미매핑은 null 이고 lane 은 유지, alternatives 없으면 기존 결과와 동일.
- [ ] 6. 회귀와 실측
  - `./gradlew test --tests '*ItineraryRanker*' --tests '*BusLaneMerger*' --tests '*OtpPlanConnection*' --tests '*OtpRoutingAdapter*' --tests '*LegacyRouteResponse*' --tests '*RouteSearchService*' --tests '*JourneyCompiler*' --tests '*RouteSearchTravelTime*' --tests '*TransferArrivalEnricher*'`
  - 전체 단위 `./gradlew test` (IT 는 Docker 필요. 실패 시 `docker ps` 먼저, 기존 실패는 "기존 무관" 라벨).
  - 실측: 운영 OTP 터널(`ssh -f -N -L 18080:127.0.0.1:18080 vps`)에 붙인 로컬 앱 또는 테스트 하네스로 서울역(37.5547,126.9707)→잠실(37.5133,127.1001), 신촌→여의도, 수원→강남 응답 lanes 확인. 기대: 서울역→잠실 5번승강장 구간 lane 6개(상한), `(예약)` 없음.
  - 결과를 `implementation_note.md` `### 검증` 에.

## 따라오는 동작 변화 (의도, 테스트로 고정 불필요하나 PR 본문에 적기)
- `JourneyCompiler.routeOptions` 가 lane 마다 routeOption 저장 → 환승 재평가 대체 후보 생김.
- `RouteSearchTravelTimeEstimator.chooseBusLane` 이 lane 중 대기 최소 선택.
- A 로 고유 경로 수가 줄면 추가 검색(기준 5) 더 자주.
- 모바일 카드 라벨은 `lanes[0].busNo` 만(`nextroute-mobile/src/features/transit/route-results-model.ts` `getSegmentLabel`) → "740 외 2" 표시는 모바일 후속.

## 범위 밖
- 강남→홍대 740 본 검색 부재 = Raptor 파레토 지배(LIST_ALL 에도 없음). 앱 버스만 추가 검색이 이미 살림.
- 운행종료 숨김(#400)이 미래 departureAt 에도 현재 실시간으로 적용(로그 `운행 종료 경로 제외 hidden=8`). 별건 이슈.
