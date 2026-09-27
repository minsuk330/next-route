package watoo.grd.nextroute.application.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.RerouteProposalView;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 재탐색이 <b>실 OTP graph</b> 위에서 끝까지 도는지.
 *
 * <p>지키는 것은 하나다 — <b>OTP 가 준 id 가 사다리 끝까지 살아남는다.</b> OTP 는
 * {@code 1:BR_TAGO_SEB100100022} 같은 GTFS id 를 주고, 예측은 서울 id({@code 100100022})로만 부를
 * 수 있다. 그 사이의 해석({@code OtpSeoulIdentityResolver})이 어긋나면 에러가 나지 않는다. 후보가
 * 조용히 "예측 없음"이 되고, 증상은 "제안이 안 만들어진다"로 나타나 예측 API 문제처럼 보인다.
 * 그래서 단언을 "제안이 있다"에서 멈추지 않고 <b>예측이 서울 id 로 불렸다</b>까지 내린다.
 *
 * <p>실패·포화·요청 모양은 {@link RerouteRemainingPathOtpIT} 가 본다(실 서버로는 못 만든다).
 *
 * <h2>돌리려면</h2>
 * <ul>
 *   <li>OTP 가 {@code OTP_IT_BASE_URL}(기본 {@code http://localhost:18080})에 떠 있어야 한다.
 *       없으면 skip 이다 — CI 에는 OTP 가 없다</li>
 *   <li>시드 id 는 graph {@code g4e098383} 에서 실측한 값이다(롯데백화점→고속터미널 = 143번).
 *       graph 를 다시 빌드해 id 가 바뀌면 여기가 깨진다. 의도된 감지다</li>
 *   <li>버스가 안 다니는 새벽(KST 23~05시)에는 skip 이다</li>
 * </ul>
 */
@SpringBootTest
@EnabledIf("otpReachable")
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.reroute.enabled=true",
        "routing.provider.default-provider=OTP",
        "routing.provider.otp-enabled=true",
        // 로컬 OTP 첫 질의는 graph 가 데워지지 않아 운영 예산(3.5s)을 넘길 수 있다.
        // 여기서 보려는 것은 속도가 아니라 id 가 이어지는지다.
        "route.search.otp-ms=15000"
})
@DisplayName("남은 구간 재검색 제안 — 실 OTP")
class RerouteRemainingPathOtpLiveIT {

    private static final String BASE_URL = Optional.ofNullable(System.getenv("OTP_IT_BASE_URL"))
            .orElse("http://localhost:18080");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final String SESSION_ID = "sess-reroute-remaining-otp-live-it";
    private static final String PATH_KEY = "0";

    /** 롯데백화점(환승) → 고속터미널(목적지). graph 가 143번 직행을 준다. */
    private static final double TRANSFER_LAT = 37.5645;
    private static final double TRANSFER_LON = 126.982;
    private static final double DESTINATION_LAT = 37.5058;
    private static final double DESTINATION_LON = 127.004;

    /** OTP {@code 1:BS_TAGO_SEB101000041} 등에서 prefix 를 뗀 서울 id. */
    private static final String STOP_LOTTE = "101000041";
    private static final String STOP_EXPRESS_TERMINAL = "121000020";
    private static final String ROUTE_143 = "100100022";

    @DynamicPropertySource
    static void otpBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("routing.otp.base-url", () -> BASE_URL);
    }

    /**
     * healthcheck 와 같은 질의. HTTP 200 이어도 errors 면 graph 가 안 올라온 것이다.
     *
     * <p>HTTP/1.1 로 고정한다. JDK HttpClient 기본값은 h2c 업그레이드를 붙이는데, OTP 는 그 POST 를
     * 받다가 멈춘다 — 타임아웃이 나서 OTP 가 떠 있어도 이 IT 가 조용히 skip 된다.
     */
    static boolean otpReachable() {
        try {
            HttpResponse<String> response = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(2)).build()
                    .send(HttpRequest.newBuilder(URI.create(BASE_URL + "/otp/routers/default/index/graphql"))
                                    .timeout(Duration.ofSeconds(5))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{feeds{feedId}}\"}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200
                    && response.body().contains("\"feedId\"")
                    && !response.body().contains("\"errors\"");
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired RerouteProposalService rerouteProposalService;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean PredictTransferUseCase predictTransferUseCase;
    @MockBean OdSayApiPort odSayApiPort;
    @MockBean RerouteCooldownPort cooldownPort;
    @MockBean TransferStopResolver transferStopResolver;
    @MockBean OtpConcurrencyGate otpGate;

    private final List<Long> createdUserIds = new ArrayList<>();
    /** 이 테스트가 넣은 정적표 행만 지운다. 시험 DB 에 원래 있던 행은 건드리지 않는다. */
    private final List<Long> seededStopIds = new ArrayList<>();
    private Long seededRouteId;
    private Long userId;
    private JourneySnapshot journey;

    @BeforeEach
    void setUp() {
        int hour = Instant.now().atZone(SEOUL).getHour();
        assumeTrue(hour >= 5 && hour < 23, "버스가 안 다니는 시간이다(KST " + hour + "시)");

        given(otpGate.tryAcquire(any(), anyString())).willReturn(Optional.of(new OtpPermit("it")));
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, plannedPath(), Instant.now().plusSeconds(600))));
        given(cooldownPort.tryAdmit(anyLong(), anyInt(), any())).willReturn(true);
        givenStaticTables();
        seedSeoulIdentity();
        userId = newUser();
        journey = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
        seededStopIds.forEach(id -> jdbcTemplate.update("delete from bus_stop where id = ?", id));
        seededStopIds.clear();
        if (seededRouteId != null) {
            jdbcTemplate.update("delete from bus_route where id = ?", seededRouteId);
            seededRouteId = null;
        }
    }

    @Test
    @DisplayName("실 graph 가 준 노선이 서울 id 로 풀려 예측까지 닿고 제안이 된다")
    void otpRouteReachesPredictionAsSeoulId() {
        Instant readyAt = Instant.now().plusSeconds(240);
        givenLiveTransfer(readyAt);
        // 지금 타려던 노선은 20분 뒤에 온다.
        givenPrediction("BL-721", readyAt.plusSeconds(1200));
        // graph 가 주는 143번은 1분 뒤에 온다. 서울 id 로 물어야만 이 답을 받는다.
        givenPrediction(ROUTE_143, readyAt.plusSeconds(60));

        Optional<RerouteProposalView> proposal =
                rerouteProposalService.request(userId, journey.journeyId(), 0);

        verify(predictTransferUseCase, atLeastOnce())
                .predict(anyString(), eq(ROUTE_143), any(), any(), any(), anyLong());
        assertThat(proposal).get().satisfies(view -> {
            assertThat(view.candidates()).isNotEmpty();
            assertThat(view.candidates().get(0).kind().name()).isEqualTo("REMAINING_PATH");
            assertThat(view.candidates().get(0).candidateId()).isNotBlank();
            assertThat(view.candidates().get(0).display().etaGainSec())
                    .as("지금 경로보다 빨라야 제안이 된다")
                    .isGreaterThanOrEqualTo(300);
        });
        assertThat(activeProposalCount()).isEqualTo(1);
        verifyNoInteractions(odSayApiPort);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    /**
     * {@code OtpSeoulIdentityResolver} 는 prefix 를 뗀 id 가 DB 에 있어야 서울 id 로 인정한다.
     * 시험 DB 는 수집기가 꺼져 있어 정적표가 비어 있다 — 없으면 phantom 으로 보고 버린다.
     */
    private void seedSeoulIdentity() {
        seedStop(STOP_LOTTE, "롯데백화점", TRANSFER_LAT, TRANSFER_LON);
        seedStop(STOP_EXPRESS_TERMINAL, "고속터미널", DESTINATION_LAT, DESTINATION_LON);
        seededRouteId = jdbcTemplate.query("""
                insert into bus_route (route_id, route_name, created_at, updated_at)
                values (?, '143', now(), now())
                on conflict (route_id) do nothing
                returning id
                """, (rs, i) -> rs.getLong(1), ROUTE_143).stream().findFirst().orElse(null);
    }

    private void seedStop(String stopId, String name, double lat, double lon) {
        jdbcTemplate.query("""
                insert into bus_stop (stop_id, stop_name, latitude, longitude, created_at, updated_at)
                values (?, ?, ?, ?, now(), now())
                on conflict (stop_id) do nothing
                returning id
                """, (rs, i) -> rs.getLong(1), stopId, name, lat, lon)
                .forEach(seededStopIds::add);
    }

    /** 정적 노선표를 가짜로 준다. {@link RerouteRemainingPathIT} 와 같은 이유다. */
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

    /**
     * 버스 둘, 환승 하나. 환승 정류장과 목적지만 실제 서울 좌표다.
     *
     * <p>갈아탈 노선을 143 으로 두면 안 된다 — graph 는 이 구간에 143 만 주는데, 지금 타려던
     * 노선과 같은 경로는 예측 전에 버려지므로 후보가 전멸한다.
     */
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
