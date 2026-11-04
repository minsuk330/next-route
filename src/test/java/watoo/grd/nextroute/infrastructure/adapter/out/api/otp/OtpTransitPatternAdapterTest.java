package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPattern;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternDetail;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternUnavailableException;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStopTime;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitTripRef;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * OTP 노선 패턴 어댑터(#265). 응답은 2026-10-07 운영 OTP 실측(trips 일부만 남긴 축약본, {@code otp/track/}).
 */
@DisplayName("OTP 노선 패턴 어댑터")
class OtpTransitPatternAdapterTest {

    private static final LocalDate DAY = LocalDate.parse("2026-10-07");
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper();

    private MockWebServer server;
    private OtpTransitPatternAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        OtpRoutingProperties props = new OtpRoutingProperties();
        props.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        adapter = new OtpTransitPatternAdapter(RestClient.builder(), mapper, props, clock);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private RoutingDeadline deadline() {
        return RoutingDeadline.of(clock, 3000);
    }

    private void enqueue(String fixture) throws IOException {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(new ClassPathResource("otp/track/" + fixture).getContentAsString(StandardCharsets.UTF_8)));
    }

    private static JsonNode json(String path) throws Exception {
        return new ObjectMapper().readTree(new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode lastBody() throws Exception {
        RecordedRequest request = server.takeRequest();
        return mapper.readTree(request.getBody().readUtf8());
    }

    @Test
    @DisplayName("좌표 주변: 버스 정류장과 지하철역(부모 없는 일반 stop)을 모드와 함께, 직선거리 가까운 순으로 읽는다")
    void stopsNear() throws Exception {
        enqueue("stops-near-gangnam.json");

        List<TransitPatternPort.NearStop> stops = adapter.stopsNear(37.4979, 127.0276, 300, 30, deadline());

        // 사각형 27개 중 모서리 1개(반경 밖)를 버린다
        assertThat(stops).hasSize(26);
        assertThat(stops).extracting(TransitPatternPort.NearStop::distanceMeters).isSorted()
                .allSatisfy(d -> assertThat(d).isLessThanOrEqualTo(300));
        TransitPatternPort.NearStop first = stops.get(0);
        assertThat(first.stop().gtfsId()).isEqualTo("1:RS_ACC1_S-1-0222");
        assertThat(first.stop().mode()).isEqualTo(RoutingMode.SUBWAY);
        assertThat(first.distanceMeters()).isEqualTo(48);
        // 방향마다 따로 오는 같은 노선 이름은 하나로 — 호선을 정하는 근거다(#313)
        assertThat(first.routeShortNames()).containsExactly("서울2호선");
        assertThat(stops).anySatisfy(n -> assertThat(n.stop().mode()).isEqualTo(RoutingMode.BUS));
        JsonNode body = lastBody();
        assertThat(body.path("operationName").asText()).isEqualTo("StopsNear");
        JsonNode vars = body.path("variables");
        // 반경 300m 를 감싸는 사각형(위도 ±0.0027°, 경도 ±0.0034°)
        assertThat(vars.path("maxLat").asDouble() - vars.path("minLat").asDouble()).isBetween(0.0053, 0.0055);
        assertThat(vars.path("maxLon").asDouble() - vars.path("minLon").asDouble()).isBetween(0.0067, 0.0069);
    }

    @Test
    @DisplayName("좌표 주변: 보행망에 못 붙는 좌표(사당역 사거리)에서도 반경 안 정류장을 준다 — stopsByRadius 는 0개였다")
    void stopsNearOffStreetCoordinate() throws Exception {
        enqueue("stops-near-sadang.json");

        List<TransitPatternPort.NearStop> stops = adapter.stopsNear(37.4765, 126.9816, 300, 30, deadline());

        assertThat(stops).isNotEmpty();
        assertThat(stops).anySatisfy(n -> assertThat(n.stop().mode()).isEqualTo(RoutingMode.SUBWAY));
        assertThat(stops).filteredOn(n -> n.stop().mode() == RoutingMode.BUS).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("좌표 주변: limit 개를 넘으면 가까운 것부터 자른다")
    void stopsNearLimitKeepsNearest() throws Exception {
        enqueue("stops-near-gangnam.json");

        List<TransitPatternPort.NearStop> stops = adapter.stopsNear(37.4979, 127.0276, 300, 3, deadline());

        assertThat(stops).extracting(n -> n.stop().gtfsId())
                .containsExactly("1:RS_ACC1_S-1-0222", "1:BS_TAGO_SEB121000091", "1:BS_TAGO_SEB121900088");
    }

    @Test
    @DisplayName("좌표 주변: 노선이 없는 정류장(역 출구 지점)은 자르기 전에 뺀다 — 가까운 자리를 차지하지 않는다")
    void stopsNearSkipsStopsWithoutRoutes() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"data\":{\"stopsByBbox\":["
                        + "{\"gtfsId\":\"1:VEX_n1_RS_ACC1_S-1-0216\",\"name\":\"잠실역 5번출구\",\"lat\":37.5133,"
                        + "\"lon\":127.1001,\"vehicleMode\":null,\"routes\":[]},"
                        + "{\"gtfsId\":\"1:RS_ACC1_S-1-0216\",\"name\":\"잠실(2호선)\",\"lat\":37.5134,"
                        + "\"lon\":127.1002,\"vehicleMode\":\"SUBWAY\",\"routes\":[{\"shortName\":\"서울2호선\"}]},"
                        + "{\"gtfsId\":\"1:BS_1\",\"name\":\"잠실역.롯데월드\",\"lat\":37.5140,"
                        + "\"lon\":127.1010,\"vehicleMode\":\"BUS\",\"routes\":[{\"shortName\":\"360\"}]}]}}"));

        List<TransitPatternPort.NearStop> stops = adapter.stopsNear(37.5133, 127.1001, 300, 2, deadline());

        assertThat(stops).extracting(n -> n.stop().gtfsId()).containsExactly("1:RS_ACC1_S-1-0216", "1:BS_1");
    }

    @Test
    @DisplayName("id 로 정류장: 없는 id 는 null 원소로 와도 건너뛴다")
    void stopsByIds() throws Exception {
        enqueue("stops-by-ids-sinbundang.json");

        List<TransitStop> stops = adapter.stops(List.of("1:BS_TAGO_SEB121000009", "1:BS_TAGO_GGB121000009",
                "1:BS_TAGO_ICB121000009", "1:BS_TAGO_XXX1"), deadline());

        assertThat(stops).extracting(TransitStop::gtfsId).containsExactly(
                "1:BS_TAGO_SEB121000009", "1:BS_TAGO_GGB121000009", "1:BS_TAGO_ICB121000009");
        assertThat(stops.get(1).name()).isEqualTo("신분당선강남역(중)");
        assertThat(stops.get(1).mode()).isEqualTo(RoutingMode.BUS);
        JsonNode body = lastBody();
        assertThat(body.path("operationName").asText()).isEqualTo("StopsByIds");
        assertThat(body.path("variables").path("ids")).hasSize(4);
    }

    @Test
    @DisplayName("id 로 정류장: 빈 목록이면 OTP 를 부르지 않는다")
    void stopsByIdsEmpty() {
        assertThat(adapter.stops(List.of(), deadline())).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("사각형 안 정류장(#326 지도 타일): 노선 없이 위치·모드만, 버스·지하철 모두")
    void stopsInBox() throws Exception {
        enqueue("stops-in-box-gangnam.json");

        List<TransitStop> stops = adapter.stopsInBox(37.494, 127.026, 37.498, 127.031, deadline());

        assertThat(stops).hasSize(24);
        assertThat(stops).filteredOn(s -> s.mode() == RoutingMode.BUS).hasSize(23);
        assertThat(stops).extracting(TransitStop::gtfsId)
                .contains("1:BS_TAGO_SEB121000009", "1:BS_TAGO_GGB121000009", "1:BS_TAGO_ICB121000009");
        JsonNode body = lastBody();
        assertThat(body.path("operationName").asText()).isEqualTo("StopsInBox");
        assertThat(body.path("variables").path("maxLon").asDouble()).isEqualTo(127.031);
    }

    @Test
    @DisplayName("정류장 패턴: headsign 은 null 그대로, 노선·정류장·trip 수를 읽는다")
    void patternsAtStop() throws Exception {
        enqueue("patterns-at-stop-bus.json");

        List<TransitPattern> patterns = adapter.patternsAtStop("1:BS_TAGO_SEB121000091", deadline());

        assertThat(patterns).hasSize(10);
        TransitPattern p640 = patterns.stream()
                .filter(p -> p.code().equals("1:BR_TAGO_SEB100100093::01")).findFirst().orElseThrow();
        assertThat(p640.headsign()).isNull();
        assertThat(p640.route().shortName()).isEqualTo("640");
        assertThat(p640.route().mode()).isEqualTo(RoutingMode.BUS);
        assertThat(p640.route().otpMode()).isEqualTo("BUS");
        assertThat(p640.route().color()).isEqualTo("0068B7");
        assertThat(p640.route().agencyGtfsId()).isEqualTo("1:A1");
        assertThat(p640.stops()).hasSize(79);
        assertThat(p640.stops()).extracting(s -> s.gtfsId()).contains("1:BS_TAGO_SEB121000091");
        assertThat(p640.tripCount()).isEqualTo(3);
        assertThat(lastBody().path("variables").path("id").asText()).isEqualTo("1:BS_TAGO_SEB121000091");
        // 축약본엔 좌표가 없다 → null
        assertThat(p640.stops().get(0).lat()).isNull();
    }

    @Test
    @DisplayName("좌표 주변: 노선 id·이름·색을 읽고, 빈 색은 null, 이름 없는 노선은 뺀다(#542)")
    void stopsNearRoutes() throws Exception {
        JsonNode root = new ObjectMapper().readTree("""
                {"data":{"stopsByBbox":[{"gtfsId":"1:S","name":"강남역","lat":37.4979,"lon":127.0276,"vehicleMode":"BUS",
                  "routes":[{"gtfsId":"1:R146","shortName":"146","color":"0068B7"},
                            {"gtfsId":"1:R740","shortName":"740","color":""},
                            {"gtfsId":"1:RX","shortName":null,"color":"000000"}]}]}}
                """);

        List<TransitPatternPort.NearStop> stops = OtpTransitPatternAdapter.parseStopsNear(root, 37.4979, 127.0276, 300, 30);

        assertThat(stops.get(0).routeShortNames()).containsExactly("146", "740");
        assertThat(stops.get(0).routes()).extracting(r -> r.gtfsId(), r -> r.shortName(), r -> r.color())
                .containsExactly(tuple("1:R146", "146", "0068B7"), tuple("1:R740", "740", null));
    }

    @Test
    @DisplayName("정류장 패턴: 패턴 정류장 좌표를 읽는다(#538 방위각)")
    void patternsAtStopCoordinates() throws Exception {
        JsonNode root = new ObjectMapper().readTree("""
                {"data":{"stop":{"patterns":[{"code":"1:P::01","route":{"gtfsId":"1:R","shortName":"146","mode":"BUS"},
                  "stops":[{"gtfsId":"1:A","name":"강남역","lat":37.4979,"lon":127.0276},{"gtfsId":"1:B","name":"역삼역"}],
                  "trips":[]}]}}}
                """);

        List<TransitPattern> patterns = OtpTransitPatternAdapter.parsePatternsAtStop(root);

        assertThat(patterns.get(0).stops().get(0).lat()).isEqualTo(37.4979);
        assertThat(patterns.get(0).stops().get(0).lon()).isEqualTo(127.0276);
        assertThat(patterns.get(0).stops().get(1).lat()).isNull();
    }

    @Test
    @DisplayName("패턴 상세: 좌표·형상을 읽는다. 지하철은 longName 이 방향을 든다")
    void patternDetail() throws Exception {
        enqueue("pattern-detail-subway2.json");

        Optional<TransitPatternDetail> detail = adapter.pattern("1:RR_ACC1_S-1-02-1I::10", deadline());

        assertThat(detail).isPresent();
        assertThat(detail.get().stops()).hasSize(87);
        assertThat(detail.get().route().longName()).isEqualTo("서울2호선(본선)<내선>");
        assertThat(detail.get().route().mode()).isEqualTo(RoutingMode.SUBWAY);
        assertThat(detail.get().geometryPoints()).isNotBlank();
    }

    @Test
    @DisplayName("낡은 패턴 코드는 비어 있다(오류 아님)")
    void missingPattern() {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"data\":{\"pattern\":null}}"));

        assertThat(adapter.pattern("1:NOPE::01", deadline())).isEmpty();
    }

    @Test
    @DisplayName("운행일 trip 과 첫 출발, trip 정차 시각")
    void tripsAndStopTimes() throws Exception {
        enqueue("trips-for-date-bus.json");
        enqueue("trip-stoptimes-bus.json");

        List<TransitTripRef> trips = adapter.tripsForDate("1:BR_TAGO_SEB100100093::01", DAY, deadline());
        assertThat(trips).first().satisfies(t -> {
            assertThat(t.gtfsId()).isEqualTo("1:BR_TAGO_SEB100100093_Ord001");
            assertThat(t.firstDepartureSec()).isEqualTo(15610);
        });
        assertThat(lastBody().path("variables").path("date").asText()).isEqualTo("2026-10-07");

        List<TransitStopTime> times = adapter.tripStopTimes("1:BR_TAGO_SEB100100093_Ord001", DAY, deadline());
        assertThat(times).hasSize(79);
        assertThat(times.get(0)).isEqualTo(new TransitStopTime("1:BS_TAGO_SEB114000190", 15600, 15610));
    }

    @Test
    @DisplayName("정차 승하차 가능 여부: NONE 만 막힘, 값이 없으면 열림(운영 실측 — 부산 공항리무진, 앞 17곳 하차 금지·공항 2곳 승차 금지)")
    void stopTimePickupDropoff() throws Exception {
        List<TransitStopTime> times = OtpTransitPatternAdapter.parseTripStopTimes(
                json("otp/track/trip-stoptimes-bus-airport.json"));

        assertThat(times).hasSize(19);
        assertThat(times.subList(0, 17)).allSatisfy(t -> {
            assertThat(t.pickup()).isTrue();
            assertThat(t.dropoff()).isFalse();
        });
        assertThat(times.subList(17, 19)).allSatisfy(t -> {
            assertThat(t.pickup()).isFalse();
            assertThat(t.dropoff()).isTrue();
        });
        assertThat(OtpTransitPatternAdapter.parseTripStopTimes(json("otp/track/trip-stoptimes-bus.json")))
                .allSatisfy(t -> assertThat(t.pickup() && t.dropoff()).isTrue());
    }

    @Test
    @DisplayName("GraphQL errors·5xx 는 TransitPatternUnavailableException")
    void failures() {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("{\"errors\":[{\"message\":\"boom\"}],\"data\":null}"));
        assertThatThrownBy(() -> adapter.patternsAtStop("1:X", deadline()))
                .isInstanceOf(TransitPatternUnavailableException.class);

        server.enqueue(new MockResponse().setResponseCode(503).setBody("down"));
        assertThatThrownBy(() -> adapter.patternsAtStop("1:X", deadline()))
                .isInstanceOf(TransitPatternUnavailableException.class);
    }

    @Test
    @DisplayName("예산이 0 이면 부르지 않는다")
    void noBudget() {
        RoutingDeadline expired = new RoutingDeadline(clock.instant().minusSeconds(1));

        assertThatThrownBy(() -> adapter.patternsAtStop("1:X", expired))
                .isInstanceOf(TransitPatternUnavailableException.class);
        assertThat(server.getRequestCount()).isZero();
    }
}
