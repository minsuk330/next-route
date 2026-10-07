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

    private JsonNode lastBody() throws Exception {
        RecordedRequest request = server.takeRequest();
        return mapper.readTree(request.getBody().readUtf8());
    }

    @Test
    @DisplayName("좌표 주변: 버스 정류장과 지하철역(부모 없는 일반 stop)을 모드와 함께 읽는다")
    void stopsNear() throws Exception {
        enqueue("stops-near-gangnam.json");

        List<TransitStop> stops = adapter.stopsNear(37.4979, 127.0276, 300, 30, deadline());

        assertThat(stops).isNotEmpty();
        assertThat(stops).anySatisfy(s -> {
            assertThat(s.gtfsId()).isEqualTo("1:RS_ACC1_S-1-0222");
            assertThat(s.mode()).isEqualTo(RoutingMode.SUBWAY);
        });
        assertThat(stops).anySatisfy(s -> assertThat(s.mode()).isEqualTo(RoutingMode.BUS));
        JsonNode body = lastBody();
        assertThat(body.path("operationName").asText()).isEqualTo("StopsNear");
        assertThat(body.path("variables").path("radius").asInt()).isEqualTo(300);
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
