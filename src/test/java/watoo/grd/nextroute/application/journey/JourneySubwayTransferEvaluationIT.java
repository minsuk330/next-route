package watoo.grd.nextroute.application.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.config.JourneyTransferRiskProperties;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.dto.SubwayRingEtaSource;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPhase;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingStage;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.out.SubwayRingPort;
import watoo.grd.nextroute.application.journey.service.JourneyDueClaimService;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationService;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationService.Result;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

/**
 * 평가기(워커)가 지하철 → 버스 환승을 판정할 때 고른 열차의 대기를 세는지 DB 까지 확인한다(#476).
 *
 * <p>평가기는 스냅샷 없이 돈다. 링을 스스로 읽어야 하므로, 고정 행(DB)에서 세대를 읽고 그 세대의 링을 찾는 배선이
 * 실제로 이어지는지가 이 시험의 관심이다. 링 저장소는 시험이 정한 값을 돌려주게 막는다 — 갱신기가 끼어들지 않게.
 *
 * <pre>
 * WAIT_SUBWAY(0) → RIDE_SUBWAY(1, 현재) → TRANSFER_WALK(2) → WAIT_BUS(3) → RIDE_BUS(4)
 * </pre>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("지하철 → 버스 환승 평가가 고른 열차의 대기를 센다")
class JourneySubwayTransferEvaluationIT {

    private static final String SESSION_ID = "sess-subway-transfer-evaluation-it";
    private static final String PATH_KEY = "0";
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 15);

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @Autowired JourneyDueClaimService claimService;
    @Autowired JourneyEvaluationService evaluationService;
    @Autowired JourneyTransferRiskProperties riskProperties;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean PredictTransferUseCase predictTransferUseCase;
    @MockBean SubwayRingPort ringPort;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private Long journeyId;
    private int rideLegIndex;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(null, subwayThenBusPath(), Instant.now().plusSeconds(600))));
        given(ringPort.find(anyLong(), anyInt(), anyInt(), anyInt())).willReturn(Optional.empty());
        startJourney();
        boardSubway();
        resolveIds();
        insertConfirmedBinding();
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    @Test
    @DisplayName("고른 열차가 승차역 전이면 저장된 준비 시각에 열차 대기가 들어가고 여유가 그만큼 준다")
    void storedReadinessIncludesChosenTrainWait() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // 열차가 2분 뒤 승차역에 와서 7분 뒤 하차역에 닿는다
        Instant trainAtAlighting = now.plusSeconds(420);
        given(ringPort.find(eq(journeyId), eq(1), eq(rideLegIndex), eq(0)))
                .willReturn(Optional.of(ringBeforeBoarding(now.plusSeconds(120), trainAtAlighting)));
        Instant busAt = now.plusSeconds(700);
        givenBusArrivesAt(busAt);

        assertThat(evaluate()).isEqualTo(Result.EVALUATED);

        Instant readyAt = trainAtAlighting.plusSeconds(riskProperties.getAlightingBufferSeconds() + walkSeconds());
        assertThat(transferColumn("current_leg_arrival_at", Instant.class)).isEqualTo(trainAtAlighting);
        assertThat(transferColumn("user_ready_at", Instant.class)).isEqualTo(readyAt);
        assertThat(transferColumn("transfer_slack_seconds", Long.class))
                .isEqualTo(busAt.getEpochSecond() - readyAt.getEpochSecond());
    }

    @Test
    @DisplayName("링이 없으면 지금처럼 단계 시작 + 계획 소요시간으로 준비 시각을 잡는다")
    void withoutRingKeepsPlannedReadiness() {
        Instant busAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(700);
        givenBusArrivesAt(busAt);

        assertThat(evaluate()).isEqualTo(Result.EVALUATED);

        Instant rideEnd = rideColumn("activated_at", Instant.class).plusSeconds(rideColumn("planned_duration_sec", Integer.class));
        assertThat(transferColumn("current_leg_arrival_at", Instant.class)).isEqualTo(rideEnd);
        assertThat(transferColumn("user_ready_at", Instant.class))
                .isEqualTo(rideEnd.plusSeconds(riskProperties.getAlightingBufferSeconds() + walkSeconds()));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private Result evaluate() {
        JourneyDueClaim claim = claimService.claimDue().stream()
                .filter(c -> c.journeyId().equals(journeyId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("여정이 선점되지 않았다"));
        return evaluationService.evaluate(claim).result();
    }

    /** 승차역(순번 0)으로 오는 중인 열차의 링. 열차 도착 예정은 실시간(LIVE)이다. */
    private SubwayRingState ringBeforeBoarding(Instant trainAtBoarding, Instant trainAtAlighting) {
        Instant now = Instant.now();
        return new SubwayRingState(journeyId, 1, rideLegIndex, 0,
                0, "1002000222", "승차역", "하차역", 2,
                BigDecimal.ZERO, now, BigDecimal.ZERO, trainAtBoarding, now.plusSeconds(600),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, now, now, 1L)
                .withSource(SubwayRingEtaSource.LIVE, SubwayRingStage.RUN)
                .withEstimatedAlightingAt(trainAtAlighting, SubwayRingEtaSource.TYPICAL);
    }

    private void givenBusArrivesAt(Instant arrivalAt) {
        willAnswer(call -> TransferPredictionResult.available(
                call.getArgument(0), call.getArgument(1), call.getArgument(2),
                TransferArrival.Source.REALTIME, Instant.now(), call.getArgument(3),
                arrivalAt, "vehicle-1", null))
                .given(predictTransferUseCase).predict(anyString(), anyString(), anyInt(), any(), any(), anyLong());
    }

    private void startJourney() {
        userId = newUser();
        JourneySnapshot journey = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
        journeyId = journey.journeyId();
        rideLegIndex = jdbcTemplate.queryForObject("""
                select leg_index from journey_leg
                 where journey_id = ? and type = 'RIDE_SUBWAY' and deleted_at is null
                 order by leg_index limit 1
                """, Integer.class, journeyId);
    }

    /** 승강장(0) → 지하철 탑승(1). 역에서 열차를 고르면 곧바로 이 단계가 된다. 단계 시작이 평가를 앞당긴다. */
    private void boardSubway() {
        var outcome = applyJourneyEventUseCase.apply(userId, journeyId,
                new JourneyCommand(UUID.randomUUID(),
                        JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        1L, 1, rideLegIndex - 1, rideLegIndex, Instant.now()));
        assertThat(outcome.result().name()).isEqualTo("APPLIED");
        assertThat(outcome.journey().currentLegIndex()).isEqualTo(rideLegIndex);
    }

    /**
     * 노선·정류장 해석 값을 채운다. 시험 DB 에는 카탈로그가 없어 시작 때 비어 있을 수 있다.
     *
     * <p>지하철 노선 id 가 없으면 실시간 없는 지하철로 보고 안내(링)를 읽지 않는다. 버스 쪽이 비면 갈아탈 차량 예측이
     * 조회를 건너뛰어 판정이 늘 UNKNOWN 이다.
     */
    private void resolveIds() {
        jdbcTemplate.update("""
                update journey_leg set route_id = '1002'
                where journey_id = ? and type in ('WAIT_SUBWAY', 'RIDE_SUBWAY') and route_id is null
                """, journeyId);
        jdbcTemplate.update("""
                update journey_leg set start_stop_id = 'ST-G'
                where journey_id = ? and type = 'WAIT_BUS' and start_stop_id is null
                """, journeyId);
        jdbcTemplate.update("""
                update journey_leg_route_option set boarding_seq = 3
                where journey_leg_id in (
                    select id from journey_leg where journey_id = ? and type = 'WAIT_BUS' and deleted_at is null)
                  and boarding_seq is null
                """, journeyId);
        jdbcTemplate.update("""
                update journey_leg_route_option set route_id = 'TEST-' || route_name
                where journey_leg_id in (
                    select id from journey_leg where journey_id = ? and type = 'WAIT_BUS' and deleted_at is null)
                  and route_id is null
                """, journeyId);
        jdbcTemplate.update("""
                update journey_leg l set route_id = o.route_id
                from journey_leg_route_option o
                where o.id = l.selected_route_option_id and l.journey_id = ? and l.type = 'WAIT_BUS'
                  and l.route_id is null
                """, journeyId);
    }

    private void insertConfirmedBinding() {
        Long rideLegId = rideColumn("id", Long.class);
        jdbcTemplate.update("""
                insert into subway_leg_binding
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     status, train_no, train_service_date, train_line_id, train_direction,
                     version, reset_generation, last_movement_seq)
                values (now(), now(), ?, ?, 1, ?, 'CONFIRMED', '2233', ?, '1002', '내선', 0, 0, 1)
                """, rideLegId, journeyId, rideLegIndex, java.sql.Date.valueOf(SERVICE_DATE));
    }

    private long walkSeconds() {
        return jdbcTemplate.queryForObject("""
                select planned_duration_sec from journey_leg
                 where journey_id = ? and type = 'TRANSFER_WALK' and deleted_at is null
                """, Long.class, journeyId);
    }

    private <T> T rideColumn(String column, Class<T> type) {
        return jdbcTemplate.queryForObject("select " + column + " from journey_leg"
                + " where journey_id = ? and leg_index = ? and deleted_at is null", type, journeyId, rideLegIndex);
    }

    private <T> T transferColumn(String column, Class<T> type) {
        return jdbcTemplate.queryForObject("select " + column + " from transfer_connection"
                + " where journey_id = ? and transfer_index = 0 and deleted_at is null", type, journeyId);
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
                delete from subway_leg_binding where journey_id in (
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
                "transfer_connection", "journey_event", "journey_notification")) {
            jdbcTemplate.update("delete from " + table
                    + " where journey_id in (select id from journey_session where user_id = ?)", id);
        }
        jdbcTemplate.update("delete from journey_session where user_id = ?", id);
    }

    /** 지하철 5분 → 걷기 3분 → 버스 15분. 버스 환승이 예측 시야(15분) 안에 들게 짧게 둔다. */
    private PathResult subwayThenBusPath() {
        return new PathResult(1, null,
                List.of(
                        SubPathFixture.subway(5).names("승차역", "하차역")
                                .subwayStations(900222, 900224).wayCode(2)
                                .lanes(SubPathFixture.subwayLane("2호선", 2))
                                .stations(
                                        new StationResult(0, "900222", "승차역", null, null),
                                        new StationResult(1, "900223", "중간역", null, null),
                                        new StationResult(2, "900224", "하차역", null, null))
                                .build(),
                        SubPathFixture.walk(3).names("하차역", "정류장G").distance(150.0).build(),
                        SubPathFixture.bus(15).names("정류장G", "정류장Z")
                                .busStops("ST-G", "ST-Z")
                                .lanes(SubPathFixture.busLane("721", "BL-721")).build()),
                List.of());
    }
}
