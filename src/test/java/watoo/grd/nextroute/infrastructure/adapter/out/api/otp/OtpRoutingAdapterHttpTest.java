package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import watoo.grd.nextroute.application.subway.service.SubwayExpressRouteFixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingFailure;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.RoutingOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingQuery;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** OtpRoutingAdapter 의 실제 HTTP 계층: 상태코드/GraphQL errors → RoutingFailure.Kind 분류. */
class OtpRoutingAdapterHttpTest {

    private MockWebServer server;
    private OtpRoutingAdapter adapter;
    private OtpRoutingProperties props;
    private OtpStationResolver stations;
    private final Instant now = Instant.parse("2026-09-08T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        props = new OtpRoutingProperties();
        props.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        // 역 판별은 따로 켜서 본다 — 켜 두면 첫 검색이 역 목록을 받으러 같은 서버에 요청을 하나 더 보낸다.
        props.setStationSnapEnabled(false);
        stations = new OtpStationResolver(RestClient.builder(), new ObjectMapper(), props, clock);
        adapter = new OtpRoutingAdapter(RestClient.builder(), new ObjectMapper(),
                new OtpPlanConnectionMapper(), props, stations, clock, SubwayExpressRouteFixtures.railServiceClassifier());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private RoutingOutcome call() {
        return call(Set.of());
    }

    private RoutingOutcome call(Set<RoutingMode> modes) {
        return call(modes, "o", "d");
    }

    private RoutingOutcome call(Set<RoutingMode> modes, String originName, String destName) {
        RoutingQuery q = new RoutingQuery(126.97, 37.56, originName, 127.02, 37.49, destName,
                now, modes, null, false, "v", RoutingProvider.OTP, null);
        return adapter.search(q, RoutingDeadline.of(clock, 3000));
    }

    @Test
    @DisplayName("200 정상 응답 → 성공")
    void ok() throws IOException {
        String body = new ClassPathResource("otp/planconnection-success.json").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(body));
        assertThat(call().success()).isTrue();
    }

    @Test
    @DisplayName("요청: 후보 20개·탐색 창 30분·버스 가중치 1.3, 나머지 수단은 전부 그대로 탐색")
    void requestCarriesCandidatePoolWindowAndBusReluctance() throws Exception {
        enqueueSuccess();

        call();

        JsonNode sent = new ObjectMapper().readTree(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertThat(sent.path("query").asText()).contains("generalizedCost", "$searchWindow", "$modes");
        JsonNode vars = sent.path("variables");
        assertThat(vars.path("first").asInt()).isEqualTo(20);
        assertThat(vars.path("searchWindow").asText()).isEqualTo("PT30M");
        JsonNode modes = vars.path("modes").path("transit").path("transit");
        // 목록을 주면 그 수단만 탐색한다 → OTP TransitMode 전부를 나열해야 탐색 범위가 그대로다.
        assertThat(modes).hasSize(15);
        java.util.Map<String, JsonNode> byMode = new java.util.HashMap<>();
        modes.forEach(m -> byMode.put(m.path("mode").asText(), m));
        assertThat(byMode).containsKeys("BUS", "SUBWAY", "RAIL", "TRAM", "MONORAIL", "FERRY", "COACH");
        assertThat(byMode.get("BUS").path("cost").path("reluctance").asDouble()).isEqualTo(1.3);
        assertThat(byMode.get("SUBWAY").has("cost")).isFalse();
    }

    @Test
    @DisplayName("검색 화면 질의(withAlternativeLegs)면 nextLegs 를 30편 묻는다(otp-bus-lane-merge)")
    void alternativeLegsRequested() throws Exception {
        enqueueSuccess();
        RoutingQuery q = new RoutingQuery(126.97, 37.56, "o", 127.02, 37.49, "d", now, Set.of(), null, false, "v",
                RoutingProvider.OTP, null).withAlternativeLegs();

        adapter.search(q, RoutingDeadline.of(clock, 3000));

        JsonNode sent = new ObjectMapper().readTree(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertThat(sent.path("query").asText())
                .contains("nextLegs(numberOfLegs: $alternativeLegs) @include(if: $withAlternatives)");
        assertThat(sent.path("variables").path("withAlternatives").asBoolean()).isTrue();
        assertThat(sent.path("variables").path("alternativeLegs").asInt()).isEqualTo(30);
        // 수단 제한 복사본(추가 검색)도 그대로 들고 간다
        assertThat(q.withModes(Set.of(RoutingMode.BUS)).alternativeLegs()).isTrue();
        assertThat(q.withExcludedRouteIds(Set.of("x")).alternativeLegs()).isTrue();
        // 전 수단 대안 검색은 nextLegs 를 뺀 복사본으로 묻는다 — 나머지 칸은 그대로
        assertThat(q.withoutAlternativeLegs().alternativeLegs()).isFalse();
        assertThat(q.withoutAlternativeLegs().withExcludedRouteIds(Set.of("x")).excludedRouteIds()).containsExactly("x");
    }

    @Test
    @DisplayName("플래그 없는 질의(재탐색 등)·버스 합치기 끔·편 수 0 이면 nextLegs 를 묻지 않는다")
    void alternativeLegsNotRequested() throws Exception {
        RoutingQuery plain = new RoutingQuery(126.97, 37.56, "o", 127.02, 37.49, "d", now, Set.of(), null, false, "v",
                RoutingProvider.OTP, null);
        enqueueSuccess();
        adapter.search(plain, RoutingDeadline.of(clock, 3000));
        assertThat(sentVars().has("withAlternatives")).isFalse();

        props.setBusLaneMergeEnabled(false);
        enqueueSuccess();
        adapter.search(plain.withAlternativeLegs(), RoutingDeadline.of(clock, 3000));
        assertThat(sentVars().has("withAlternatives")).isFalse();

        props.setBusLaneMergeEnabled(true);
        props.setAlternativeLegs(0);
        enqueueSuccess();
        adapter.search(plain.withAlternativeLegs(), RoutingDeadline.of(clock, 3000));
        assertThat(sentVars().has("withAlternatives")).isFalse();
    }

    private JsonNode sentVars() throws Exception {
        return new ObjectMapper().readTree(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8())
                .path("variables");
    }

    @Test
    @DisplayName("가중치 1.0·창 미지정이면 그 변수를 보내지 않는다 — OTP 기본 탐색과 같다")
    void neutralSettingsOmitVariables() throws Exception {
        props.setBusReluctance(1.0);
        props.setSearchWindow(null);
        enqueueSuccess();

        call();

        JsonNode vars = new ObjectMapper().readTree(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8())
                .path("variables");
        assertThat(vars.has("modes")).isFalse();
        assertThat(vars.has("searchWindow")).isFalse();
    }

    @Test
    @DisplayName("30분 창 안에 출발편이 없으면 창을 빼고 한 번 더 묻는다 — 배차 긴 지방·심야가 '경로 없음'이 되지 않게")
    void noConnectionInWindowRetriesWithDynamicWindow() throws Exception {
        // 2026-10-12 실측: 홍천→인제 14:00, 30분 창 = 0건(다음 시외버스 14:40), 자동 창(1h20m) = 2건.
        enqueueNoConnection("NO_TRANSIT_CONNECTION_IN_SEARCH_WINDOW");
        enqueueSuccess();

        RoutingOutcome outcome = call();

        assertThat(outcome.success()).isTrue();
        assertThat(server.getRequestCount()).isEqualTo(2);
        JsonNode first = sentVariables();
        JsonNode retry = sentVariables();
        assertThat(first.path("searchWindow").asText()).isEqualTo("PT30M");
        assertThat(retry.has("searchWindow")).as("재검색은 OTP 자동 창").isFalse();
        assertThat(retry.path("modes")).isEqualTo(first.path("modes"));
    }

    @Test
    @DisplayName("창과 무관한 무경로는 다시 묻지 않는다")
    void otherNoRouteDoesNotRetry() throws Exception {
        enqueueNoConnection("NO_TRANSIT_CONNECTION");

        RoutingOutcome outcome = call();

        assertThat(((RoutingFailure) outcome).kind()).isEqualTo(RoutingFailure.Kind.NO_ROUTE);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("창을 안 보냈으면 다시 묻지 않는다 — 이미 자동 창이다")
    void noWindowNoRetry() throws Exception {
        props.setSearchWindow(null);
        enqueueNoConnection("NO_TRANSIT_CONNECTION_IN_SEARCH_WINDOW");

        RoutingOutcome outcome = call();

        assertThat(((RoutingFailure) outcome).kind()).isEqualTo(RoutingFailure.Kind.NO_ROUTE);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("지하철·철도만 물으면 버스 계열은 빼고 레일 계열만 보낸다")
    void railOnlyModes() throws Exception {
        enqueueSuccess();

        call(Set.of(RoutingMode.SUBWAY, RoutingMode.RAIL));

        assertThat(sentModeNames()).containsExactlyInAnyOrder("SUBWAY", "MONORAIL", "TRAM", "RAIL", "FUNICULAR");
    }

    @Test
    @DisplayName("버스만 물으면 버스 계열만, 버스 가중치는 그대로")
    void busOnlyModesKeepReluctance() throws Exception {
        enqueueSuccess();

        call(Set.of(RoutingMode.BUS));

        JsonNode modes = sentVariables().path("modes").path("transit").path("transit");
        java.util.Map<String, JsonNode> byMode = new java.util.HashMap<>();
        modes.forEach(m -> byMode.put(m.path("mode").asText(), m));
        assertThat(byMode).containsOnlyKeys("BUS", "COACH", "TROLLEYBUS");
        assertThat(byMode.get("BUS").path("cost").path("reluctance").asDouble()).isEqualTo(1.3);
    }

    @Test
    @DisplayName("가중치 1.0 이어도 수단 제한은 보낸다 — 제한이 목적이다")
    void restrictionSentEvenWithoutReluctance() throws Exception {
        props.setBusReluctance(1.0);
        enqueueSuccess();

        call(Set.of(RoutingMode.SUBWAY));

        assertThat(sentModeNames()).containsExactlyInAnyOrder("SUBWAY", "MONORAIL", "TRAM");
    }

    @Test
    @DisplayName("제외 노선이 있으면 preferences.transit.filters[].exclude.routes 로 보낸다(급행 끄기, subway-express S2)")
    void excludedRoutesSentAsTransitFilter() throws Exception {
        enqueueSuccess();
        RoutingQuery q = new RoutingQuery(126.97, 37.56, "o", 127.02, 37.49, "d", now, Set.of(), null, false, "v",
                RoutingProvider.OTP, null, Set.of("1:RR_ACC1_S-1-09-2D", "1:RR_ACC1_S-1-09-2U"));

        adapter.search(q, RoutingDeadline.of(clock, 3000));

        JsonNode sent = new ObjectMapper().readTree(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertThat(sent.path("query").asText()).contains("$preferences: PlanPreferencesInput",
                "preferences: $preferences", "trip { gtfsId pattern { code } }");
        JsonNode filters = sent.path("variables").path("preferences").path("transit").path("filters");
        assertThat(filters).hasSize(1);
        java.util.List<String> routes = new java.util.ArrayList<>();
        filters.get(0).path("exclude").get(0).path("routes").forEach(r -> routes.add(r.asText()));
        // 공항철도 직통은 요청과 무관하게 언제나 같이 뺀다(D3, #495)
        assertThat(routes).containsExactly("1:RR_ACC1_S-1-09-2D", "1:RR_ACC1_S-1-09-2U",
                "1:RR_ACC4_NATE971_D", "1:RR_ACC4_NATE971_U");
    }

    @Test
    @DisplayName("제외 노선이 없어도 공항철도 직통(RR_ACC4_NATE971_U/D)은 언제나 탐색에서 뺀다 — D3, #495")
    void directRoutesAlwaysExcluded() throws Exception {
        enqueueSuccess();

        call();

        JsonNode filters = sentVariables().path("preferences").path("transit").path("filters");
        assertThat(filters).hasSize(1);
        java.util.List<String> routes = new java.util.ArrayList<>();
        filters.get(0).path("exclude").get(0).path("routes").forEach(r -> routes.add(r.asText()));
        assertThat(routes).containsExactly("1:RR_ACC4_NATE971_D", "1:RR_ACC4_NATE971_U");
    }

    @Test
    @DisplayName("운영 실측(2026-10-22 서울역→인천공항1터미널 10:00, 역 id 출발): 직통 구간은 DIRECT, 공항철도 본선은 NORMAL")
    void liveArexDirectAnnotatedDirect() throws Exception {
        String body = new ClassPathResource("otp/planconnection-arex-direct.json")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(body));

        RoutingOutcome outcome = call();

        java.util.List<watoo.grd.nextroute.application.route.routing.RoutingItinerary> its =
                ((watoo.grd.nextroute.application.route.routing.RoutingResult) outcome).itineraries();
        assertThat(its).extracting(it -> it.legs().get(0).sourceRouteId())
                .containsExactly("1:RR_ACC4_NATE971_D", "1:RR_ACC1_S-1-AP-1D");
        assertThat(its).extracting(it -> it.legs().get(0).trainKind()).containsExactly(
                watoo.grd.nextroute.application.subway.dto.TrainKind.DIRECT,
                watoo.grd.nextroute.application.subway.dto.TrainKind.NORMAL);
    }

    @Test
    @DisplayName("수단 제한 복사본(추가 검색)도 제외 노선을 그대로 들고 간다")
    void withModesKeepsExclusion() {
        RoutingQuery q = new RoutingQuery(126.97, 37.56, "o", 127.02, 37.49, "d", now, Set.of(), null, false, "v",
                RoutingProvider.OTP, null, Set.of("1:X"));

        assertThat(q.withModes(Set.of(RoutingMode.BUS)).excludedRouteIds()).containsExactly("1:X");
        assertThat(q.withExcludedRouteIds(Set.of()).excludedRouteIds()).isEmpty();
    }

    @Test
    @DisplayName("응답 철도 구간에 열차 종류가 붙어 나온다 — 판정은 어댑터 경계 한 곳(annotate)")
    void annotatesTrainKind() throws Exception {
        String body = new ClassPathResource("otp/planconnection-subway.json")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(body));

        RoutingOutcome outcome = call();

        watoo.grd.nextroute.application.route.routing.RoutingLeg subway =
                ((watoo.grd.nextroute.application.route.routing.RoutingResult) outcome).itineraries().get(0)
                        .legs().get(0);
        assertThat(subway.trainKind()).isEqualTo(watoo.grd.nextroute.application.subway.dto.TrainKind.NORMAL);
    }

    private java.util.List<String> sentModeNames() throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
        sentVariables().path("modes").path("transit").path("transit")
                .forEach(m -> names.add(m.path("mode").asText()));
        return names;
    }

    @Test
    @DisplayName("출발 장소가 역이고 그 승강장이 가까우면 승강장의 가상 역 id 로 묻는다 — 가까운 출구에서 걷는다")
    void stationPlaceUsesPlatform() throws Exception {
        // 쿼리 출발 (37.56, 126.97). 승강장은 약 22m 북쪽, 같은 역의 버스 정류장은 무시한다.
        loadStations(37.5602, 126.97, "SUBWAY");
        enqueueSuccess();

        call(Set.of(), "가역 2호선", "d");

        JsonNode vars = sentVariables();
        assertThat(vars.path("origin").path("location").path("stopLocation").path("stopLocationId").asText())
                .isEqualTo("1:STN_RS_A");
        assertThat(vars.path("destination").path("location").has("coordinate")).isTrue();
    }

    @Test
    @DisplayName("승강장에서 150m 넘게 떨어지면 좌표 그대로")
    void farFromPlatformKeepsCoordinate() throws Exception {
        loadStations(37.5620, 126.97, "SUBWAY"); // ≈ 222m
        enqueueSuccess();

        call(Set.of(), "가역", "d");

        assertThat(sentVariables().path("origin").path("location").has("coordinate")).isTrue();
    }

    @Test
    @DisplayName("버스 정류장만 있는 역은 판별하지 않는다")
    void busOnlyStationIsIgnored() throws Exception {
        loadStations(37.5601, 126.97, "BUS");
        enqueueSuccess();

        call(Set.of(), "가역", "d");

        assertThat(sentVariables().path("origin").path("location").has("coordinate")).isTrue();
    }

    @Test
    @DisplayName("역 id 검색이 GraphQL 오류면 좌표로 한 번 더 묻는다")
    void stationErrorFallsBackToCoordinates() throws Exception {
        loadStations(37.5602, 126.97, "SUBWAY");
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"errors\":[{\"message\":\"Unknown stop\"}],\"data\":null}"));
        enqueueSuccess();

        RoutingOutcome outcome = call(Set.of(), "가역", "d");

        assertThat(outcome.success()).isTrue();
        assertThat(sentVariables().path("origin").path("location").has("stopLocation")).isTrue();
        assertThat(sentVariables().path("origin").path("location").has("coordinate")).isTrue();
    }

    @Test
    @DisplayName("이름이 역이 아니면 승강장 옆이어도 좌표 — 출구·정류장에 선 GPS 출발을 승강장으로 붙이지 않는다")
    void nonStationNameKeepsCoordinate() throws Exception {
        loadStations(37.5602, 126.97, "SUBWAY");
        enqueueSuccess();
        enqueueSuccess();
        enqueueSuccess();

        call(Set.of(), "현재 위치", "d");
        call(Set.of(), "가역 스타벅스", "d");
        call(Set.of(), null, "d");

        for (int i = 0; i < 3; i++) {
            assertThat(sentVariables().path("origin").path("location").has("coordinate")).isTrue();
        }
    }

    @Test
    @DisplayName("다른 역 이름이면 붙이지 않는다")
    void otherStationNameKeepsCoordinate() throws Exception {
        loadStations(37.5602, 126.97, "SUBWAY");
        enqueueSuccess();

        call(Set.of(), "나역", "d");

        assertThat(sentVariables().path("origin").path("location").has("coordinate")).isTrue();
    }

    @Test
    @DisplayName("출발·도착이 같은 역이면 둘 다 좌표")
    void sameStationBothEndsKeepsCoordinates() throws Exception {
        props.setStationSnapEnabled(true);
        // 출발(37.56,126.97)·도착(37.49,127.02) 옆에 같은 역의 두 승강장
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"data\":{\"stations\":["
                        + "{\"gtfsId\":\"1:VST_P1\",\"name\":\"가\",\"stops\":["
                        + "{\"gtfsId\":\"1:P1\",\"name\":\"가(1호선)\",\"lat\":37.5601,\"lon\":126.97,\"vehicleMode\":\"SUBWAY\"}]},"
                        + "{\"gtfsId\":\"1:VST_P2\",\"name\":\"가\",\"stops\":["
                        + "{\"gtfsId\":\"1:P2\",\"name\":\"가(2호선)\",\"lat\":37.4901,\"lon\":127.02,\"vehicleMode\":\"SUBWAY\"}]}]}}"));
        stations.load();
        server.takeRequest(1, TimeUnit.SECONDS);
        enqueueSuccess();

        call(Set.of(), "가역", "가역");

        JsonNode vars = sentVariables();
        assertThat(vars.path("origin").path("location").has("coordinate")).isTrue();
        assertThat(vars.path("destination").path("location").has("coordinate")).isTrue();
    }

    @Test
    @DisplayName("노선 없는 역 이름은 모든 노선 역 — 어느 노선 승강장으로도 0분에 출발·도착")
    void lineLessNameUsesAllLinesStation() throws Exception {
        loadAllLinesStation();
        enqueueSuccess();
        enqueueSuccess();

        call(Set.of(), "가역", "d");
        call(Set.of(), "가역 (고속철도)", "d");

        for (int i = 0; i < 2; i++) {
            assertThat(sentVariables().path("origin").path("location").path("stopLocation").path("stopLocationId")
                    .asText()).isEqualTo("1:VSA_G");
        }
    }

    @Test
    @DisplayName("노선 있는 역 이름은 그 승강장의 가상 역 — 모든 노선 역이 있어도")
    void lineNameUsesPlatformStationEvenWithAllLines() throws Exception {
        loadAllLinesStation();
        enqueueSuccess();

        call(Set.of(), "가역 2호선", "d");

        assertThat(sentVariables().path("origin").path("location").path("stopLocation").path("stopLocationId")
                .asText()).isEqualTo("1:VST_P1");
    }

    @Test
    @DisplayName("모든 노선 역은 승강장 복제 좌표로 거리를 잰다 — 일반철도 승강장 옆 역사도 붙는다")
    void allLinesStationMatchesByPlatformCopies() throws Exception {
        // 출발 (37.56,126.97): 지하철 승강장 P1 은 ≈ 222m(반경 밖), KTX 승강장 복제는 ≈ 11m
        props.setStationSnapEnabled(true);
        server.enqueue(stationsResponse("["
                + "{\"gtfsId\":\"1:VST_P1\",\"name\":\"가\",\"stops\":["
                + "{\"gtfsId\":\"1:P1\",\"name\":\"가(1호선)\",\"lat\":37.5620,\"lon\":126.97,\"vehicleMode\":\"SUBWAY\"}]},"
                + "{\"gtfsId\":\"1:VSA_G\",\"name\":\"가\",\"stops\":["
                + "{\"gtfsId\":\"1:VSA_P_P1\",\"name\":\"가(1호선)\",\"lat\":37.5620,\"lon\":126.97,\"vehicleMode\":null},"
                + "{\"gtfsId\":\"1:VSA_P_K\",\"name\":\"가\",\"lat\":37.5601,\"lon\":126.97,\"vehicleMode\":null}]}]"));
        stations.load();
        server.takeRequest(1, TimeUnit.SECONDS);
        enqueueSuccess();
        enqueueSuccess();

        call(Set.of(), "가역", "d");
        call(Set.of(), "가역 1호선", "d");

        assertThat(sentVariables().path("origin").path("location").path("stopLocation").path("stopLocationId")
                .asText()).isEqualTo("1:VSA_G");
        assertThat(sentVariables().path("origin").path("location").has("coordinate")).isTrue();
    }

    @Test
    @DisplayName("모든 노선 역이 없으면(승강장 1개 역) 노선 없는 이름도 승강장의 가상 역")
    void lineLessNameFallsBackToPlatformStation() throws Exception {
        loadStations(37.5602, 126.97, "SUBWAY");
        enqueueSuccess();

        call(Set.of(), "가역", "d");

        assertThat(sentVariables().path("origin").path("location").path("stopLocation").path("stopLocationId")
                .asText()).isEqualTo("1:STN_RS_A");
    }

    @Test
    @DisplayName("역 이름 장소 판별")
    void stationKey() {
        assertThat(OtpStationResolver.stationKey("잠실역 8호선")).isEqualTo("잠실");
        assertThat(OtpStationResolver.stationKey("강남역 신분당선")).isEqualTo("강남");
        assertThat(OtpStationResolver.stationKey("역삼역")).isEqualTo("역삼");
        assertThat(OtpStationResolver.stationKey("총신대입구(이수)역")).isEqualTo("총신대입구");
        assertThat(OtpStationResolver.stationKey("홍대입구")).isEqualTo("홍대입구");
        assertThat(OtpStationResolver.stationKey("강남역 스타벅스")).isNull();
        assertThat(OtpStationResolver.stationKey("홍대입구역 9번출구")).isNull();
        assertThat(OtpStationResolver.stationKey("서울역 롯데마트")).isNull();
        assertThat(OtpStationResolver.stationKey("현재 위치")).isNull();
        assertThat(OtpStationResolver.baseName("잠실(8호선)")).isEqualTo("잠실");
        assertThat(OtpStationResolver.baseName("서울역")).isEqualTo("서울");
    }

    @Test
    @DisplayName("노선 지정 여부: 노선 꼬리만 노선, 괄호 설명은 노선 아님")
    void placeNameLine() {
        assertThat(OtpStationResolver.parse("서울역")).isEqualTo(new OtpStationResolver.PlaceName("서울", false));
        assertThat(OtpStationResolver.parse("서울역 (고속철도)"))
                .isEqualTo(new OtpStationResolver.PlaceName("서울", false));
        assertThat(OtpStationResolver.parse("총신대입구(이수)역"))
                .isEqualTo(new OtpStationResolver.PlaceName("총신대입구", false));
        assertThat(OtpStationResolver.parse("잠실역 8호선")).isEqualTo(new OtpStationResolver.PlaceName("잠실", true));
        assertThat(OtpStationResolver.parse("서울역 공항철도")).isEqualTo(new OtpStationResolver.PlaceName("서울", true));
        assertThat(OtpStationResolver.parse("서울역 GTX-A")).isEqualTo(new OtpStationResolver.PlaceName("서울", true));
        assertThat(OtpStationResolver.parse("서울역 경의중앙선")).isEqualTo(new OtpStationResolver.PlaceName("서울", true));
        assertThat(OtpStationResolver.parse("왕십리역(5호선)")).isEqualTo(new OtpStationResolver.PlaceName("왕십리", true));
        assertThat(OtpStationResolver.parse("신촌역(경의중앙선)")).isEqualTo(new OtpStationResolver.PlaceName("신촌", true));
        assertThat(OtpStationResolver.parse("서울역 KTX")).isEqualTo(new OtpStationResolver.PlaceName("서울", false));
        assertThat(OtpStationResolver.parse("GS25 강남역점")).isNull();
    }

    /** VST_P1(가 2호선 승강장 P1) + 모든 노선 역 VSA_G(P1·P2 복제 + 출구 복제). 출발 (37.56,126.97) 옆. */
    private void loadAllLinesStation() throws Exception {
        props.setStationSnapEnabled(true);
        server.enqueue(stationsResponse("["
                + "{\"gtfsId\":\"1:VST_P1\",\"name\":\"가\",\"stops\":["
                + "{\"gtfsId\":\"1:P1\",\"name\":\"가(2호선)\",\"lat\":37.5602,\"lon\":126.97,\"vehicleMode\":\"SUBWAY\"},"
                + "{\"gtfsId\":\"1:VEX_n1_P1\",\"name\":\"가역 1번 출구\",\"lat\":37.5600,\"lon\":126.97,\"vehicleMode\":null}]},"
                + "{\"gtfsId\":\"1:VSA_G\",\"name\":\"가\",\"stops\":["
                + "{\"gtfsId\":\"1:VSA_P_P1\",\"name\":\"가(2호선)\",\"lat\":37.5602,\"lon\":126.97,\"vehicleMode\":null},"
                + "{\"gtfsId\":\"1:VSA_P_P2\",\"name\":\"가(8호선)\",\"lat\":37.5610,\"lon\":126.97,\"vehicleMode\":null},"
                + "{\"gtfsId\":\"1:VSA_X_n1_P1\",\"name\":\"가역 1번 출구\",\"lat\":37.5600,\"lon\":126.97,\"vehicleMode\":null}]}]"));
        stations.load();
        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8()).contains("stations");
    }

    private static MockResponse stationsResponse(String stationsJson) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"data\":{\"stations\":" + stationsJson + "}}");
    }

    /** 역 목록을 동기로 적재한다(그 요청은 여기서 소비). 승강장 하나 + 같은 역 버스 정류장 하나. */
    private void loadStations(double lat, double lon, String mode) throws Exception {
        props.setStationSnapEnabled(true);
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"data\":{\"stations\":[{\"gtfsId\":\"1:STN_RS_A\",\"name\":\"가\",\"stops\":["
                        + "{\"gtfsId\":\"1:RS_A\",\"name\":\"가(2호선)\",\"lat\":" + lat + ",\"lon\":" + lon
                        + ",\"vehicleMode\":\"" + mode + "\"},"
                        + "{\"gtfsId\":\"1:BS_1\",\"name\":\"가역\",\"lat\":37.56,\"lon\":126.97,\"vehicleMode\":\"BUS\"}]}]}}"));
        stations.load();
        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8()).contains("stations");
    }

    private void enqueueNoConnection(String code) {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":{\"planConnection\":{\"edges\":[],\"routingErrors\":[{\"code\":\""
                        + code + "\",\"description\":\"x\"}]}}}"));
    }

    private JsonNode sentVariables() throws Exception {
        return new ObjectMapper().readTree(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8())
                .path("variables");
    }

    private void enqueueSuccess() throws IOException {
        String body = new ClassPathResource("otp/planconnection-success.json").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(body));
    }

    @Test
    @DisplayName("5xx → ENGINE_ERROR")
    void serverError() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("boom"));
        RoutingOutcome outcome = call();
        assertThat(outcome.success()).isFalse();
        assertThat(((RoutingFailure) outcome).kind()).isEqualTo(RoutingFailure.Kind.ENGINE_ERROR);
    }

    @Test
    @DisplayName("HTTP 200 + GraphQL top-level errors → ENGINE_ERROR(부분응답 방지)")
    void graphqlErrors() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"errors\":[{\"message\":\"boom\"}],\"data\":null}"));
        RoutingOutcome outcome = call();
        assertThat(outcome.success()).isFalse();
        assertThat(((RoutingFailure) outcome).kind()).isEqualTo(RoutingFailure.Kind.ENGINE_ERROR);
        assertThat(((RoutingFailure) outcome).code()).isEqualTo("GRAPHQL_ERROR");
    }

    @Test
    @DisplayName("헤더 뒤 본문이 예산을 넘기면 TIMEOUT — 예외 원문을 메시지에 싣지 않는다")
    void bodyReadTimeoutIsTimeout() {
        // 2026-10-05 prod 재현: Content-Type 없이 헤더만 먼저 오고 본문이 예산을 넘겼다.
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"data\":{}}")
                .setBodyDelay(5, TimeUnit.SECONDS));

        RoutingFailure failure = (RoutingFailure) call();

        assertThat(failure.kind()).isEqualTo(RoutingFailure.Kind.TIMEOUT);
        assertThat(failure.message()).isEqualTo("OTP 응답 시간 초과")
                .doesNotContain("extracting");
    }

    @Test
    @DisplayName("응답 헤더 전에 예산을 넘겨도 TIMEOUT")
    void headerReadTimeoutIsTimeout() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{}")
                .setHeadersDelay(5, TimeUnit.SECONDS));

        assertThat(((RoutingFailure) call()).kind()).isEqualTo(RoutingFailure.Kind.TIMEOUT);
    }
}
