package watoo.grd.nextroute.application.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.out.RerouteCooldownPort;
import watoo.grd.nextroute.application.journey.service.RerouteProposalService;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.service.TransferStopResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 재탐색이 <b>OTP 로 나갈 때만</b> 성립하는 계약. OTP 서버 자리에 {@link MockWebServer} 를 세운다.
 *
 * <p>실 graph 로 사다리가 끝까지 도는지는 {@link RerouteRemainingPathOtpLiveIT} 가 본다. 여기는
 * 실 서버로는 만들 수 없는 상황을 맡는다 — 나간 요청을 꺼내 보는 것, 서버가 죽는 것, gate 가
 * 막는 것.
 *
 * <ul>
 *   <li>출발 시각이 "지금"이 아니라 <b>원점에 서는 시각</b>이다. ODSAY 에는 이 칸이 없어서
 *       {@link RerouteRemainingPathIT} 로는 원리적으로 볼 수 없다</li>
 *   <li>OTP 가 죽어도 예외가 아니라 빈 결과이고, <b>ODSAY 로 폴백하지 않는다</b></li>
 *   <li>gate 가 permit 을 안 주면 OTP 를 아예 부르지 않는다</li>
 * </ul>
 *
 * <p>OTP 앞의 모든 것(어댑터 HTTP·GraphQL 파싱·legacy 매핑)은 진짜다. 가짜는 서버의 응답뿐이다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.reroute.enabled=true",
        // 코드 기본값도 OTP 지만 env 로 뒤집힐 수 있다. 이 IT 의 전제라 못박는다.
        "routing.provider.default-provider=OTP",
        "routing.provider.otp-enabled=true"
})
@DisplayName("남은 구간 재검색 제안 — OTP 계약")
class RerouteRemainingPathOtpIT {

    private static final String SESSION_ID = "sess-reroute-remaining-otp-it";
    private static final String PATH_KEY = "0";

    /** 컨텍스트보다 먼저 떠야 base-url 을 줄 수 있다. 컨텍스트가 캐시되므로 서버도 끝까지 산다. */
    private static final MockWebServer OTP = new MockWebServer();
    /** 테스트마다 비운다. 서버의 요청 수는 누적이라 그대로 쓰면 앞 테스트가 섞인다. */
    private static final List<RecordedRequest> RECEIVED = new CopyOnWriteArrayList<>();

    static {
        try {
            OTP.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void otpBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("routing.otp.base-url", () -> OTP.url("/").toString().replaceAll("/$", ""));
    }

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired RerouteProposalService rerouteProposalService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean PredictTransferUseCase predictTransferUseCase;
    @MockBean OdSayApiPort odSayApiPort;
    @MockBean RerouteCooldownPort cooldownPort;
    @MockBean TransferStopResolver transferStopResolver;
    @MockBean OtpConcurrencyGate otpGate;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private JourneySnapshot journey;

    @BeforeEach
    void setUp() {
        RECEIVED.clear();
        respondWith(200, fixture("otp/planconnection-success.json"));
        // Mockito 는 Optional 을 비워서 돌려준다 — 그대로 두면 "포화"다. permit 을 주는 게 기본이다.
        given(otpGate.tryAcquire(any(), anyString())).willReturn(Optional.of(new OtpPermit("it")));
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, plannedPath(), Instant.now().plusSeconds(600))));
        given(cooldownPort.tryAdmit(anyLong(), anyInt(), any())).willReturn(true);
        givenStaticTables();
        userId = newUser();
        journey = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    @Test
    @DisplayName("OTP 에 묻는 출발 시각은 '지금'이 아니라 원점에 서는 시각이다")
    void asksOtpForTheTimeTheUserStandsAtTheOrigin() throws IOException {
        Instant readyAt = Instant.now().plusSeconds(600);
        givenLiveTransfer(readyAt);
        givenPrediction("BL-721", readyAt.plusSeconds(1200));

        rerouteProposalService.request(userId, journey.journeyId(), 0);

        assertThat(RECEIVED).as("계획 하차점 한 자리에서 한 번 묻는다").hasSize(1);
        JsonNode variables = objectMapper.readTree(
                RECEIVED.get(0).getBody().readString(StandardCharsets.UTF_8)).path("variables");
        Instant asked = OffsetDateTime.parse(variables.path("when").asText()).toInstant();
        // 지금으로 물으면 OTP 는 사용자가 거기 서기 전에 떠난 편을 답으로 준다.
        assertThat(Duration.between(Instant.now(), asked))
                .as("지금이 아니다")
                .isGreaterThan(Duration.ofMinutes(5));
        assertThat(asked).isCloseTo(readyAt, within(Duration.ofSeconds(1)));
        assertThat(variables.at("/origin/location/coordinate/latitude").asDouble())
                .as("출발점은 사용자가 입력한 출발지가 아니라 환승 정류장이다")
                .isEqualTo(TRANSFER_LAT);
        assertThat(variables.at("/origin/location/coordinate/longitude").asDouble())
                .isEqualTo(TRANSFER_LON);
        assertThat(variables.at("/destination/location/coordinate/latitude").asDouble())
                .isEqualTo(DESTINATION_LAT);
    }

    @Test
    @DisplayName("OTP 가 500 이면 예외가 아니라 빈 결과이고 ODSAY 로 넘어가지 않는다")
    void otpServerErrorIsEmptyWithoutOdsayFallback() {
        respondWith(500, "{\"message\":\"boom\"}");
        Instant readyAt = Instant.now().plusSeconds(240);
        givenLiveTransfer(readyAt);
        givenPrediction("BL-721", readyAt.plusSeconds(1200));

        assertThat(rerouteProposalService.request(userId, journey.journeyId(), 0)).isEmpty();

        assertThat(RECEIVED).as("OTP 에는 실제로 물었다").hasSize(1);
        assertThat(activeProposalCount()).isZero();
        verifyNoInteractions(odSayApiPort);
    }

    @Test
    @DisplayName("OTP 가 GraphQL 오류를 주면(HTTP 200) 빈 결과이고 ODSAY 로 넘어가지 않는다")
    void otpGraphQlErrorIsEmptyWithoutOdsayFallback() {
        respondWith(200, "{\"errors\":[{\"message\":\"Validation error\"}]}");
        Instant readyAt = Instant.now().plusSeconds(240);
        givenLiveTransfer(readyAt);
        givenPrediction("BL-721", readyAt.plusSeconds(1200));

        assertThat(rerouteProposalService.request(userId, journey.journeyId(), 0)).isEmpty();

        assertThat(RECEIVED).hasSize(1);
        assertThat(activeProposalCount()).isZero();
        verifyNoInteractions(odSayApiPort);
    }

    @Test
    @DisplayName("gate 가 permit 을 안 주면 OTP 를 부르지 않는다")
    void saturatedGateSkipsOtp() {
        given(otpGate.tryAcquire(any(), anyString())).willReturn(Optional.empty());
        Instant readyAt = Instant.now().plusSeconds(240);
        givenLiveTransfer(readyAt);
        givenPrediction("BL-721", readyAt.plusSeconds(1200));

        assertThat(rerouteProposalService.request(userId, journey.journeyId(), 0)).isEmpty();

        assertThat(RECEIVED).as("과부하 방어가 OTP 앞에서 막는다").isEmpty();
        assertThat(activeProposalCount()).isZero();
        verifyNoInteractions(odSayApiPort);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    /** 롯데백화점(환승) → 고속터미널(목적지). 픽스처 응답과 같은 좌표다. */
    private static final double TRANSFER_LAT = 37.5645;
    private static final double TRANSFER_LON = 126.982;
    private static final double DESTINATION_LAT = 37.5058;
    private static final double DESTINATION_LON = 127.004;

    private static void respondWith(int status, String body) {
        OTP.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                RECEIVED.add(request);
                return new MockResponse().setResponseCode(status)
                        .setHeader("Content-Type", "application/json")
                        .setBody(body);
            }
        });
    }

    private static String fixture(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 정적 노선표를 가짜로 준다. {@link RerouteRemainingPathIT#givenStaticTables} 와 같은 이유다. */
    private void givenStaticTables() {
        given(transferStopResolver.resolveStop(anyString())).willAnswer(call ->
                Optional.of(new TransferStopResolver.StopResolution(call.getArgument(0))));
        given(transferStopResolver.resolveRoute(anyString(), any())).willAnswer(call ->
                Optional.of(new TransferStopResolver.RouteResolution(
                        call.getArgument(0), call.getArgument(1), false, null)));
        given(transferStopResolver.resolveSeq(anyString(), anyString()))
                .willReturn(new TransferStopResolver.SeqResolution(List.of(3)));
    }

    private void givenLiveTransfer(Instant readyAt) {
        jdbcTemplate.update("""
                update transfer_connection
                   set status = 'AT_RISK', user_ready_at = ?, confidence = 'GOOD',
                       current_leg_arrival_at = ?, calculated_at = now()
                 where journey_id = ? and transfer_index = 0 and deleted_at is null
                """, java.sql.Timestamp.from(readyAt), java.sql.Timestamp.from(readyAt),
                journey.journeyId());
    }

    private void givenPrediction(String routeId, Instant arrivalAt) {
        given(predictTransferUseCase.predict(anyString(), eq(routeId),
                any(), any(), any(), anyLong()))
                .willAnswer(call -> TransferPredictionResult.available(
                        call.getArgument(0), routeId, call.getArgument(2),
                        TransferArrival.Source.REALTIME, Instant.now(), call.getArgument(3),
                        arrivalAt, null, null));
    }

    private int activeProposalCount() {
        return jdbcTemplate.queryForObject("""
                select count(*) from reroute_proposal
                 where journey_id = ? and status = 'ACTIVE'
                """, Integer.class, journey.journeyId());
    }

    /** 버스 둘, 환승 하나. 환승 정류장과 목적지만 실제 서울 좌표다. */
    private PathResult plannedPath() {
        return new PathResult(1,
                new PathInfo(60, 4000, 900, 1, "정류장A", "고속터미널", null),
                List.of(
                        SubPathFixture.bus(20).names("정류장A", "정류장F")
                                .coords(126.95, 37.58, 126.980, 37.566)
                                .busStops("ST-A", "ST-F")
                                .lanes(SubPathFixture.busLane("370", "BL-370")).build(),
                        SubPathFixture.walk(3).names("정류장F", "롯데백화점").distance(150.0)
                                .coords(126.980, 37.566, TRANSFER_LON, TRANSFER_LAT).build(),
                        SubPathFixture.bus(40).names("롯데백화점", "고속터미널")
                                .coords(TRANSFER_LON, TRANSFER_LAT, DESTINATION_LON, DESTINATION_LAT)
                                .busStops("ST-G", "ST-Z")
                                .lanes(SubPathFixture.busLane("721", "BL-721")).build()),
                List.of());
    }

    private Long newUser() {
        Long id = jdbcTemplate.queryForObject("""
                insert into users (created_at, updated_at, token_generation)
                values (now(), now(), 0) returning id
                """, Long.class);
        createdUserIds.add(id);
        return id;
    }

    private void deleteJourneysOf(Long id) {
        jdbcTemplate.update("""
                delete from reroute_proposal where journey_id in (
                    select id from journey_session where user_id = ?)
                """, id);
        jdbcTemplate.update("""
                update journey_leg set selected_route_option_id = null
                where journey_id in (select id from journey_session where user_id = ?)
                """, id);
        jdbcTemplate.update("""
                delete from journey_leg_route_option where journey_leg_id in (
                    select l.id from journey_leg l join journey_session s on s.id = l.journey_id
                    where s.user_id = ?)
                """, id);
        for (String table : List.of("journey_leg", "journey_revision",
                "transfer_connection", "journey_event")) {
            jdbcTemplate.update("delete from " + table
                    + " where journey_id in (select id from journey_session where user_id = ?)", id);
        }
        jdbcTemplate.update("""
                delete from journey_notification where journey_id in (
                    select id from journey_session where user_id = ?)
                """, id);
        jdbcTemplate.update("delete from journey_session where user_id = ?", id);
    }
}
