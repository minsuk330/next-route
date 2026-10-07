package watoo.grd.nextroute.domain.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.testsupport.AbstractJourneyOutboxPostgresIT;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 단독 추적({@code kind = TRACK}, #265)은 재탐색 보드에 들어가지 않는다 — SQL 에서 막는다.
 *
 * <p>세션을 읽은 뒤 자바에서 거르면 읽기와 쓰기 사이에 창이 생긴다. 그래서 보드 선점·예약·신호·수락 기록
 * 쿼리가 모두 {@code kind = 'NAVIGATION'} 을 조건으로 가진다. 이 시험은 그 조건이 빠지면 깨진다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaConfig.class)
@Sql(scripts = {
        "classpath:db/it/journey-reset.sql",
        "classpath:db/migration/V35__create_journey.sql",
        "classpath:db/migration/V36__add_journey_transit_resolution.sql",
        "classpath:db/migration/V37__align_journey_navigation_contract.sql",
        "classpath:db/migration/V38__add_journey_last_progress_id.sql",
        "classpath:db/migration/V42__create_reroute_proposal.sql",
        "classpath:db/migration/V43__add_reroute_proposal_accepted_candidate.sql",
        "classpath:db/migration/V50__add_reroute_proposal_origin.sql",
        "classpath:db/migration/V51__reroute_proposal_journey_active_unique.sql",
        "classpath:db/migration/V52__drop_reroute_proposal_transfer_index.sql",
        "classpath:db/migration/V53__add_reroute_alert_state.sql",
        "classpath:db/migration/V54__add_reroute_proposal_alerted.sql",
        "classpath:db/migration/V57__reroute_board.sql",
        "classpath:db/migration/V58__reroute_refresh_claim.sql",
        "classpath:db/migration/V59__journey_session_kind.sql",
        "classpath:db/migration/V61__journey_leg_vehicle.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("단독 추적은 재탐색 보드에서 빠진다")
class JourneyKindRerouteExclusionIT extends AbstractJourneyOutboxPostgresIT {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final Instant FAR_FUTURE = Instant.parse("9999-01-01T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JourneySessionRepository sessionRepository;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbcTemplate.update("delete from reroute_proposal");
        jdbcTemplate.update("delete from journey_session");
        jdbcTemplate.update("delete from users");
    }

    @Test
    @DisplayName("kind 를 안 주면 NAVIGATION 으로 저장된다 — 기존 시작 경로 회귀")
    void defaultsToNavigation() {
        Long id = newSession(null);

        assertThat(sessionRepository.findById(id)).get()
                .extracting(JourneySession::getKind).isEqualTo(JourneyKind.NAVIGATION);
        assertThat(jdbcTemplate.queryForObject(
                "select kind from journey_session where id = ?", String.class, id))
                .isEqualTo("NAVIGATION");
    }

    @Test
    @DisplayName("값 목록 밖의 kind 는 DB 가 거절한다")
    void rejectsUnknownKind() {
        Long id = newSession(JourneyKind.TRACK);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update journey_session set kind = 'FOO' where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("기한이 지나도 TRACK 은 선점되지 않는다")
    void claimSkipsTrack() {
        Long navigation = newSession(JourneyKind.NAVIGATION);
        Long track = newSession(JourneyKind.TRACK);
        // 예약이 어떤 길로든 심겼다고 가정한다(심기는 것 자체도 아래에서 막지만, 선점이 따로 막는지 본다).
        setRefreshAt(navigation, NOW.minusSeconds(10));
        setRefreshAt(track, NOW.minusSeconds(10));

        List<Long> claimed = tx.execute(status ->
                sessionRepository.claimRerouteRefreshBatch(NOW, NOW.plusSeconds(60), 10));

        assertThat(claimed).containsExactly(navigation);
        assertThat(claimedUntil(track)).isNull();
    }

    @Test
    @DisplayName("갱신 신호는 TRACK 에 예약을 만들지 않는다")
    void requestDoesNotScheduleTrack() {
        Long navigation = newSession(JourneyKind.NAVIGATION);
        Long track = newSession(JourneyKind.TRACK);

        int navigationRows = tx.execute(status ->
                sessionRepository.requestRerouteRefresh(navigation, NOW, 30, true, FAR_FUTURE));
        int trackRows = tx.execute(status ->
                sessionRepository.requestRerouteRefresh(track, NOW, 30, true, FAR_FUTURE));

        assertThat(navigationRows).isEqualTo(1);
        assertThat(refreshAt(navigation)).isEqualTo(NOW);
        assertThat(trackRows).isZero();
        assertThat(refreshAt(track)).isNull();
    }

    @Test
    @DisplayName("첫 예약도 TRACK 에는 심기지 않는다")
    void initialScheduleSkipsTrack() {
        Long navigation = newSession(JourneyKind.NAVIGATION);
        Long track = newSession(JourneyKind.TRACK);

        int navigationRows = tx.execute(status ->
                sessionRepository.scheduleRerouteRefreshIfAbsent(navigation, NOW));
        int trackRows = tx.execute(status ->
                sessionRepository.scheduleRerouteRefreshIfAbsent(track, NOW));

        assertThat(navigationRows).isEqualTo(1);
        assertThat(trackRows).isZero();
        assertThat(refreshAt(track)).isNull();
    }

    @Test
    @DisplayName("수락 시각 기록도 TRACK 에는 쓰지 않는다")
    void acceptedMarkSkipsTrack() {
        Long track = newSession(JourneyKind.TRACK);

        int rows = tx.execute(status -> sessionRepository.markRerouteAccepted(track, NOW));

        assertThat(rows).isZero();
        assertThat(sessionRepository.findRerouteAcceptedAt(track)).isEmpty();
    }

    private Long newSession(JourneyKind kind) {
        long userId = jdbcTemplate.queryForObject(
                "insert into users (created_at, updated_at) values (now(), now()) returning id",
                Long.class);
        JourneySession session = JourneySession.builder()
                .userId(userId)
                .kind(kind)
                .startedAt(NOW.minusSeconds(300))
                .guidanceLeaseUntil(NOW.plusSeconds(180))
                .routeSnapshot(MAPPER.createObjectNode())
                .build();
        return sessionRepository.saveAndFlush(session).getId();
    }

    private void setRefreshAt(Long id, Instant at) {
        jdbcTemplate.update("update journey_session set reroute_refresh_at = ? where id = ?",
                Timestamp.from(at), id);
    }

    private Instant refreshAt(Long id) {
        Timestamp at = jdbcTemplate.queryForObject(
                "select reroute_refresh_at from journey_session where id = ?", Timestamp.class, id);
        return at == null ? null : at.toInstant();
    }

    private Instant claimedUntil(Long id) {
        Timestamp at = jdbcTemplate.queryForObject(
                "select reroute_refresh_claimed_until from journey_session where id = ?",
                Timestamp.class, id);
        return at == null ? null : at.toInstant();
    }
}
