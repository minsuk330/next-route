package watoo.grd.nextroute.application.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.JourneyDueClaimService;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationService;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationService.Result;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.testsupport.MutableClock;

import java.time.Instant;
import java.time.ZoneId;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

/**
 * 앱이 조회하지 않아도 서버 판정이 계속 새로 계산된다.
 *
 * <p>평가기 전에는 판정이 조회 요청 안에서만 계산됐다. 앱을 닫으면 조회가 없고, 그러면 계산도
 * 없어서 환승이 위험해져도 알릴 근거가 생기지 않았다. 이 시험은 <b>조회 API 를 한 번도 부르지
 * 않고</b> 평가 주기만 돌린다.
 *
 * <p>평가 주기는 스케줄러가 하는 일(선점 → 평가)을 그대로 부른다. 스케줄러 빈은 worker 프로필
 * 전용이라 시험 컨텍스트에는 없다. 다음 주기는 DB 를 손으로 과거로 돌리지 않고, 평가기가 심은
 * 다음 평가 시각까지 시계를 넘겨 자연스럽게 만기가 되게 한다.
 *
 * <p>갱신의 증거는 계산 시각({@code calculated_at})이다. 평가 번호는 앱에 보일 값이 바뀔 때만
 * 오르므로, 값이 그대로인 평범한 주기에서는 다시 계산해도 그대로다 — 번호를 증거로 삼으면 멀쩡한
 * 갱신을 실패로 본다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("조회가 없어도 판정이 갱신된다")
class JourneyRefreshWithoutQueryIT {

    private static final String SESSION_ID = "sess-refresh-without-query-it";
    private static final String PATH_KEY = "0";

    @TestConfiguration
    static class ClockConfig {
        /** 마이크로초로 자른다. 선점 시각을 저장 값과 {@code =} 로 비교하는데 Postgres 는 마이크로초까지만 담는다. */
        @Bean
        @Primary
        MutableClock refreshClock() {
            return new MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @Autowired JourneyDueClaimService claimService;
    @Autowired JourneyEvaluationService evaluationService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MutableClock clock;
    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean PredictTransferUseCase predictTransferUseCase;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private Long journeyId;

    @BeforeEach
    void setUp() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(null, busPath(), Instant.now().plusSeconds(600))));
        journeyId = startJourney();
        boardFirstBus();
        resolveStopIdentity();
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    @Test
    @DisplayName("값이 그대로여도 주기마다 다시 계산한다 — 평가 번호는 오르지 않는다")
    void recomputesEveryCycleWithoutQuery() {
        givenNextBusAt(clock.instant().plusSeconds(1_800));

        assertThat(runNextCycle()).isEqualTo(Result.EVALUATED);
        Instant firstCalculatedAt = storedCalculatedAt();
        long firstVersion = evaluationVersion();

        assertThat(runNextCycle()).isEqualTo(Result.EVALUATED);

        assertThat(storedCalculatedAt())
                .as("조회가 없어도 판정을 새로 계산했다")
                .isAfter(firstCalculatedAt);
        assertThat(evaluationVersion())
                .as("값이 같으면 번호는 그대로다 — 갱신의 증거로 쓰면 안 되는 이유")
                .isEqualTo(firstVersion);
        assertThat(storedStatus()).isNotEqualTo(TransferStatus.UNKNOWN.name());
    }

    @Test
    @DisplayName("조회가 없는 사이 환승이 위험해지면 위험 알림이 쌓인다")
    void enqueuesRiskWithoutQuery() {
        givenNextBusAt(clock.instant().plusSeconds(1_800));
        runNextCycle();
        assertThat(storedStatus()).isEqualTo(TransferStatus.COMFORTABLE.name());
        assertThat(notifications()).isEmpty();

        // 갈아탈 버스가 앞당겨져, 타고 있는 사람이 내려 걸어가면 이미 지나간 뒤다.
        givenNextBusAt(clock.instant().plusSeconds(60));
        runNextCycle();

        assertThat(storedStatus()).isEqualTo(TransferStatus.MISSED.name());
        assertThat(notifications())
                .as("앱이 닫혀 있어도 서버가 위험을 알릴 근거를 만든다")
                .isNotEmpty();
    }

    // ── 평가 주기 ────────────────────────────────────────────────────────────

    /**
     * 다음 평가 주기를 한 번 돈다.
     *
     * <p>평가기가 심어 둔 다음 평가 시각까지 시계를 넘긴 뒤 선점·평가한다. 처음에는 여정 시작과
     * 탑승 전이가 심은 "지금"이 이미 만기다.
     */
    private Result runNextCycle() {
        Instant due = nextEvaluationAt();
        if (due.isAfter(clock.instant())) {
            clock.set(due);
        }
        JourneyDueClaim claim = claimService.claimDue().stream()
                .filter(c -> c.journeyId().equals(journeyId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("여정이 선점되지 않았다"));
        return evaluationService.evaluate(claim).result();
    }

    /** 갈아탈 버스의 도착 시각을 절대 시각으로 고정한다. 시계를 넘겨도 같은 값이다. */
    private void givenNextBusAt(Instant arrivalAt) {
        willAnswer(call -> TransferPredictionResult.available(
                call.getArgument(0), call.getArgument(1), call.getArgument(2),
                TransferArrival.Source.REALTIME, clock.instant(), call.getArgument(3),
                arrivalAt, "vehicle-1", null))
                .given(predictTransferUseCase).predict(anyString(), anyString(), anyInt(), any(), any(), anyLong());
    }

    // ── 관찰 ────────────────────────────────────────────────────────────────

    private String storedStatus() {
        return transferColumn("status", String.class);
    }

    private Instant storedCalculatedAt() {
        return transferColumn("calculated_at", Instant.class);
    }

    private <T> T transferColumn(String column, Class<T> type) {
        return jdbcTemplate.queryForObject(
                "select " + column + " from transfer_connection"
                        + " where journey_id = ? and transfer_index = 0 and deleted_at is null",
                type, journeyId);
    }

    private Instant nextEvaluationAt() {
        return jdbcTemplate.queryForObject(
                "select next_evaluation_at from journey_session where id = ?", Instant.class, journeyId);
    }

    private long evaluationVersion() {
        return jdbcTemplate.queryForObject(
                "select evaluation_version from journey_session where id = ?", Long.class, journeyId);
    }

    private List<String> notifications() {
        return jdbcTemplate.queryForList(
                "select checkpoint from journey_notification where journey_id = ? order by id",
                String.class, journeyId);
    }

    // ── 준비 (JourneyEvaluationIT 와 같은 여정) ─────────────────────────────

    private Long startJourney() {
        userId = newUser();
        JourneySnapshot journey = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
        return journey.journeyId();
    }

    /** 대기(0) → 주행(1). 타고 있어야 하차 버퍼와 환승 도보가 더해진다. */
    private void boardFirstBus() {
        var outcome = applyJourneyEventUseCase.apply(userId, journeyId,
                new JourneyCommand(UUID.randomUUID(), JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        1L, 1, 0, 1, clock.instant()));
        assertThat(outcome.result().name()).isEqualTo("APPLIED");
    }

    /**
     * 예측기가 조회를 건너뛰지 않도록 갈아탈 정류장·노선·승차 순번을 채운다.
     *
     * <p>노선 id 는 실제로는 노선표가 채운다. 비어 있으면 예측기가 "노선 미해석"으로 건너뛴다.
     */
    private void resolveStopIdentity() {
        jdbcTemplate.update("""
                update journey_leg set start_stop_id = 'ST-G'
                where journey_id = ? and type = 'WAIT_BUS' and leg_index > 0
                  and start_stop_id is null
                """, journeyId);
        jdbcTemplate.update("""
                update journey_leg_route_option
                   set boarding_seq = coalesce(boarding_seq, 3),
                       route_id = coalesce(route_id, 'BL-721')
                where journey_leg_id in (
                    select id from journey_leg
                    where journey_id = ? and type = 'WAIT_BUS' and leg_index > 0 and deleted_at is null)
                """, journeyId);
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

    /** 첫 버스 5분, 도보 3분, 둘째 버스 15분. 환승이 예측 시야(15분) 안에 들어온다. */
    private PathResult busPath() {
        return new PathResult(1,
                new PathInfo(25, 2500, 600, 1, "정류장A", "정류장Z", null),
                List.of(
                        SubPathFixture.bus(5).names("정류장A", "정류장F")
                                .busStops("ST-A", "ST-F")
                                .lanes(SubPathFixture.busLane("370", "BL-370")).build(),
                        SubPathFixture.walk(3).names("정류장F", "정류장G").distance(150.0).build(),
                        SubPathFixture.bus(15).names("정류장G", "정류장Z")
                                .busStops("ST-G", "ST-Z")
                                .lanes(SubPathFixture.busLane("721", "BL-721")).build()),
                List.of());
    }
}
