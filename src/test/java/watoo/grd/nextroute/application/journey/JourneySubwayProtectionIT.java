package watoo.grd.nextroute.application.journey;

import watoo.grd.nextroute.testsupport.DbInstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyProgressCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyProgressOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneyProgressOutcome.Result;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyProgressUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 지하철 구간의 보호 시각이 진행도 보고로 어떻게 움직이는지 DB 까지 확인한다.
 *
 * <p>단계 전이 때 세우는 값은 검색이 준 계획 소요시간에서 나온다. 실제 지하철이 늦으면 그 시각이
 * 지나 버리고, 앱이 정상인데도 여정이 만료 대상이 된다. 그래서 앱이 남은 시간을 보고할 때마다
 * 다시 세우되, 단계 시작 + 60분은 넘기지 않는다(SC-12 3항).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("지하철 보호 시각은 관측으로 갱신되고 60분을 넘지 않는다")
class JourneySubwayProtectionIT {

    private static final String SESSION_ID = "sess-subway-protection-it";
    private static final String PATH_KEY = "0";

    /** 지하철 주행 단계. 0=대기, 1=주행, 2=도보 순으로 쪼개진다. */
    private static final int RIDE_LEG = 1;
    private static final int WALK_LEG = 2;

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @Autowired ApplyJourneyProgressUseCase applyJourneyProgressUseCase;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean RouteSearchSnapshotPort snapshotPort;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private JourneySnapshot journey;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, subwayPath(), Instant.now().plusSeconds(600))));
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
    @DisplayName("지하철에 들어가면 보호 시각이 세워진다 — 계획 소요시간 + 5분")
    void enteringSubwaySetsProtection() {
        JourneySnapshot riding = enterSubway();

        assertThat(riding.currentLeg().type()).isEqualTo(JourneyLegType.RIDE_SUBWAY);
        assertThat(riding.subwayOfflineProtectionUntil()).isNotNull();
        // 계획 15분 + 여유 5분이므로 lease(3분)보다 훨씬 뒤다.
        assertThat(riding.subwayOfflineProtectionUntil()).isAfter(riding.guidanceLeaseUntil());
    }

    @Test
    @DisplayName("실제로 더 걸린다는 보고가 오면 보호 시각을 뒤로 옮긴다")
    void progressExtendsProtectionWithinCap() {
        Instant planned = enterSubway().subwayOfflineProtectionUntil();

        // 앱이 "이 단계가 아직 30분 남았다"고 보고한다. 계획(15분)보다 늦게 도착하는 상황이다.
        JourneyProgressOutcome outcome = sendProgress(RIDE_LEG, Instant.now(), 1800);

        assertThat(outcome.result()).isEqualTo(Result.APPLIED);
        assertThat(outcome.journey().subwayOfflineProtectionUntil()).isAfter(planned);
        assertThat(storedProtection()).isEqualTo(DbInstants.micros(outcome.journey().subwayOfflineProtectionUntil()));
        assertThat(storedProtection()).isBeforeOrEqualTo(cap());
    }

    @Test
    @DisplayName("아무리 오래 걸린다고 해도 단계 시작 + 60분을 넘지 않는다")
    void neverExceedsOneHourCap() {
        enterSubway();

        // 두 시간이 남았다는 보고. 상한을 넘어가는 값이다.
        sendProgress(RIDE_LEG, Instant.now(), 7200);

        assertThat(storedProtection()).isEqualTo(cap());
    }

    @Test
    @DisplayName("반영되지 않은 관측은 보호 시각을 움직이지 않는다")
    void staleProgressDoesNotMoveProtection() {
        enterSubway();
        Instant observedAt = Instant.now();
        sendProgress(RIDE_LEG, observedAt, 1800);
        Instant afterFirst = storedProtection();

        // 더 오래된 관측이 뒤늦게 도착했다. 반영되지 않으므로 보호 시각도 그대로다.
        JourneyProgressOutcome stale =
                sendProgress(RIDE_LEG, observedAt.minusSeconds(60), 3000);

        assertThat(stale.result()).isEqualTo(Result.STALE);
        assertThat(storedProtection()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("재전송은 보호 시각을 두 번 움직이지 않는다")
    void resendDoesNotMoveProtection() {
        enterSubway();
        UUID progressId = UUID.randomUUID();
        Instant observedAt = Instant.now();
        applyJourneyProgressUseCase.apply(userId, journey.journeyId(),
                progress(progressId, RIDE_LEG, observedAt, 1800));
        Instant afterFirst = storedProtection();

        JourneyProgressOutcome again = applyJourneyProgressUseCase.apply(userId, journey.journeyId(),
                progress(progressId, RIDE_LEG, observedAt, 1800));

        assertThat(again.result()).isEqualTo(Result.DUPLICATE);
        assertThat(storedProtection()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("지하철을 벗어나면 보호가 사라지고, 이후 진행도 보고가 되살리지 않는다")
    void leavingSubwayClearsProtection() {
        enterSubway();
        sendProgress(RIDE_LEG, Instant.now(), 1800);

        JourneySnapshot walking = transition(2L, RIDE_LEG, WALK_LEG);
        assertThat(walking.currentLeg().type()).isEqualTo(JourneyLegType.WALK);
        assertThat(walking.subwayOfflineProtectionUntil()).isNull();

        JourneyProgressOutcome outcome = sendProgress(WALK_LEG, Instant.now(), 300);

        assertThat(outcome.result()).isEqualTo(Result.APPLIED);
        assertThat(outcome.journey().subwayOfflineProtectionUntil()).isNull();
        assertThat(storedProtection()).isNull();
    }

    @Test
    @DisplayName("남은 시간을 안 보내도 보호는 유지된다 — 계획 소요시간으로 다시 세운다")
    void keepsProtectionWithoutRemainingDuration() {
        enterSubway();

        sendProgress(RIDE_LEG, Instant.now(), null);

        assertThat(storedProtection()).isNotNull();
        assertThat(storedProtection()).isBeforeOrEqualTo(cap());
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    /** 대기(0) → 주행(1) 전이. 이때 보호 시각이 처음 세워진다. */
    private JourneySnapshot enterSubway() {
        return transition(1L, 0, RIDE_LEG);
    }

    private JourneySnapshot transition(long baseCommandVersion, int fromLeg, int toLeg) {
        return applyJourneyEventUseCase.apply(userId, journey.journeyId(),
                new JourneyCommand(UUID.randomUUID(),
                        JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        baseCommandVersion, journey.routeRevision(),
                        fromLeg, toLeg, Instant.now())).journey();
    }

    private JourneyProgressOutcome sendProgress(int legIndex, Instant observedAt,
                                                Integer remainingDurationSeconds) {
        return applyJourneyProgressUseCase.apply(userId, journey.journeyId(),
                progress(UUID.randomUUID(), legIndex, observedAt, remainingDurationSeconds));
    }

    private JourneyProgressCommand progress(UUID progressId, int legIndex, Instant observedAt,
                                            Integer remainingDurationSeconds) {
        return new JourneyProgressCommand(progressId, journey.routeRevision(), legIndex,
                1, BigDecimal.valueOf(0.4), BigDecimal.valueOf(0.4),
                "sec-1", 1200, remainingDurationSeconds, 3, observedAt, "GOOD");
    }

    /** 상한 = 단계 시작 + 60분. 단계 시작 시각은 DB 가 가진 값을 쓴다. */
    private Instant cap() {
        Instant activatedAt = jdbcTemplate.queryForObject("""
                select activated_at from journey_leg
                where journey_id = ? and leg_index = ? and deleted_at is null
                """, Instant.class, journey.journeyId(), RIDE_LEG);
        return activatedAt.plusSeconds(3600);
    }

    private Instant storedProtection() {
        return jdbcTemplate.queryForObject("""
                select subway_offline_protection_until from journey_session where id = ?
                """, Instant.class, journey.journeyId());
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
                "transfer_connection", "journey_event")) {
            jdbcTemplate.update("delete from " + table
                    + " where journey_id in (select id from journey_session where user_id = ?)", id);
        }
        // 알림 outbox 는 여정을 FK 로 잡고 있다. 평가가 위험을 감지하면 여기에도 행이 생기므로
        // 여정보다 먼저 지운다.
        jdbcTemplate.update("""
                delete from journey_notification where journey_id in (
                    select id from journey_session where user_id = ?)
                """, id);
        jdbcTemplate.update("delete from journey_session where user_id = ?", id);
    }

    private PathResult subwayPath() {
        return new PathResult(1, null,
                List.of(
                        SubPathFixture.subway(15).names("강남", "신촌")
                                .subwayStations(900222, 900240).wayCode(2)
                                .lanes(SubPathFixture.subwayLane("2호선", 2)).build(),
                        SubPathFixture.walk(5).names("신촌", "목적지").distance(300.0).build()),
                List.of());
    }
}
