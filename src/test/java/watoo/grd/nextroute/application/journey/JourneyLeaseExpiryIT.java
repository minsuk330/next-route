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
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.JourneyLeaseExpiryService;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 만료 처리가 언제 접고 언제 기다리는지 DB 까지 확인한다.
 *
 * <p>경계가 세 개다. lease 만 지난 상태(기다린다), 유예까지 지난 상태(접는다), 지하철 보호 시각이
 * 남은 상태(기다린다). 세 경계 중 하나만 틀려도 정상 사용 중인 사용자의 여정이 끊기거나 죽은
 * 여정이 계속 외부 API 를 쓴다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("만료 처리는 lease·유예·지하철 보호를 모두 지난 여정만 접는다")
class JourneyLeaseExpiryIT {

    private static final String SESSION_ID = "sess-expiry-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired JourneyLeaseExpiryService expiryService;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean RouteSearchSnapshotPort snapshotPort;

    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, busPath(), Instant.now().plusSeconds(600))));
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    @Test
    @DisplayName("lease 와 유예가 모두 지나면 EXPIRED/CLIENT_LEASE_EXPIRED 로 접는다")
    void expiresAfterLeaseAndGrace() {
        Long journeyId = startJourney();
        setLeaseAge(journeyId, "3 minutes");

        List<Long> expired = expiryService.expireBatch();

        assertThat(expired).contains(journeyId);
        assertThat(statusOf(journeyId)).isEqualTo("EXPIRED");
        assertThat(endReasonOf(journeyId)).isEqualTo("CLIENT_LEASE_EXPIRED");
        assertThat(endedAtOf(journeyId)).isNotNull();
    }

    @Test
    @DisplayName("평가 대상에서 함께 빼야 한다 — next_evaluation_at 을 같은 문장에서 비운다")
    void clearsNextEvaluationAt() {
        Long journeyId = startJourney();
        setLeaseAge(journeyId, "3 minutes");
        jdbcTemplate.update("""
                update journey_session set next_evaluation_at = now() where id = ?
                """, journeyId);

        expiryService.expireBatch();

        assertThat(nextEvaluationAtOf(journeyId)).isNull();
    }

    @Test
    @DisplayName("lease 는 지났지만 유예 안이면 기다린다 — 늦게 도착한 background callback")
    void waitsInsideGrace() {
        Long journeyId = startJourney();
        // lease 는 30초 전에 끝났다. 유예 2분이 남아 있으므로 아직 접지 않는다.
        setLeaseAge(journeyId, "30 seconds");

        List<Long> expired = expiryService.expireBatch();

        assertThat(expired).doesNotContain(journeyId);
        assertThat(statusOf(journeyId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("지하철 보호 시각이 남아 있으면 lease·유예가 지나도 기다린다")
    void waitsWhileSubwayProtected() {
        Long journeyId = startJourney();
        setLeaseAge(journeyId, "10 minutes");
        jdbcTemplate.update("""
                update journey_session
                set subway_offline_protection_until = now() + interval '10 minutes'
                where id = ?
                """, journeyId);

        List<Long> expired = expiryService.expireBatch();

        assertThat(expired).doesNotContain(journeyId);
        assertThat(statusOf(journeyId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("보호 시각까지 지나면 접고, 남은 보호 시각도 지운다")
    void expiresAfterSubwayProtectionPasses() {
        Long journeyId = startJourney();
        setLeaseAge(journeyId, "70 minutes");
        jdbcTemplate.update("""
                update journey_session
                set subway_offline_protection_until = now() - interval '5 minutes'
                where id = ?
                """, journeyId);

        List<Long> expired = expiryService.expireBatch();

        assertThat(expired).contains(journeyId);
        assertThat(statusOf(journeyId)).isEqualTo("EXPIRED");
        assertThat(subwayProtectionOf(journeyId)).isNull();
    }

    @Test
    @DisplayName("이미 끝난 여정은 다시 건드리지 않는다")
    void alreadyEndedIsUntouched() {
        Long journeyId = startJourney();
        setLeaseAge(journeyId, "3 minutes");
        expiryService.expireBatch();
        Instant firstEndedAt = endedAtOf(journeyId);

        List<Long> again = expiryService.expireBatch();

        assertThat(again).doesNotContain(journeyId);
        assertThat(endedAtOf(journeyId)).isEqualTo(firstEndedAt);
    }

    @Test
    @DisplayName("두 워커가 동시에 돌아도 같은 여정을 두 번 접지 않는다")
    void concurrentWorkersDoNotDoubleClaim() throws Exception {
        List<Long> targets = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Long journeyId = startJourney();
            setLeaseAge(journeyId, "3 minutes");
            targets.add(journeyId);
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<List<Long>> claim = expiryService::expireBatch;
            List<Future<List<Long>>> futures = pool.invokeAll(List.of(claim, claim));

            List<Long> first = futures.get(0).get();
            List<Long> second = futures.get(1).get();

            // 겹치면 같은 여정을 두 번 접었다는 뜻이다. SKIP LOCKED 가 막아야 하는 지점.
            assertThat(Collections.disjoint(first, second)).isTrue();
            List<Long> claimed = new ArrayList<>(first);
            claimed.addAll(second);
            assertThat(claimed).containsExactlyInAnyOrderElementsOf(targets);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    /**
     * 사용자 한 명과 그 사람의 진행 중인 여정 하나.
     *
     * <p>{@code uq_journey_active_per_user} 때문에 여정 여러 개를 만들려면 사용자도 그만큼 필요하다.
     */
    private Long startJourney() {
        Long ownerId = newUser();
        JourneySnapshot journey = startJourneyUseCase.start(ownerId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
        return journey.journeyId();
    }

    /** lease 가 끝난 지 얼마나 됐는지를 직접 만든다. 시계를 흔들지 않고 경계를 넘긴다. */
    private void setLeaseAge(Long journeyId, String interval) {
        jdbcTemplate.update("""
                update journey_session
                set guidance_lease_until = now() - interval '%s',
                    last_heartbeat_at = now() - interval '%s'
                where id = ?
                """.formatted(interval, interval), journeyId);
    }

    private String statusOf(Long journeyId) {
        return jdbcTemplate.queryForObject(
                "select status from journey_session where id = ?", String.class, journeyId);
    }

    private String endReasonOf(Long journeyId) {
        return jdbcTemplate.queryForObject(
                "select end_reason from journey_session where id = ?", String.class, journeyId);
    }

    private Instant endedAtOf(Long journeyId) {
        return jdbcTemplate.queryForObject(
                "select ended_at from journey_session where id = ?", Instant.class, journeyId);
    }

    private Instant nextEvaluationAtOf(Long journeyId) {
        return jdbcTemplate.queryForObject(
                "select next_evaluation_at from journey_session where id = ?",
                Instant.class, journeyId);
    }

    private Instant subwayProtectionOf(Long journeyId) {
        return jdbcTemplate.queryForObject(
                "select subway_offline_protection_until from journey_session where id = ?",
                Instant.class, journeyId);
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
        jdbcTemplate.update("delete from journey_session where user_id = ?", id);
    }

    private PathResult busPath() {
        return new PathResult(1,
                new PathInfo(25, 2000, 500, 1, "정류장A", "정류장F", null),
                List.of(
                        SubPathFixture.bus(20).names("정류장A", "정류장F")
                                .busStops("ST-A", "ST-F")
                                .lanes(SubPathFixture.busLane("370", "BL-370")).build(),
                        SubPathFixture.walk(3).names("정류장F", "목적지").distance(150.0).build()),
                List.of());
    }
}
