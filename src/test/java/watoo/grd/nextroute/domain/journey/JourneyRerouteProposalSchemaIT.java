package watoo.grd.nextroute.domain.journey;

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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.testsupport.AbstractJourneyOutboxPostgresIT;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V42 가 만든 표의 모양을 실 PostgreSQL 로 고정한다.
 *
 * <p>이 파일의 존재 이유는 <b>부분 unique</b> 다. 전체 unique 로 걸면 지난 제안이 자리를 잡아
 * 같은 환승에서 두 번째 제안을 영영 못 만든다 — {@code journey_notification.delivery_key} 에서
 * 겪은 그대로다. "만료로 바꾼 뒤 삽입이 된다"를 직접 확인한다.
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
        "classpath:db/migration/V39__create_user_push_token.sql",
        "classpath:db/migration/V40__create_journey_notification.sql",
        "classpath:db/migration/V41__create_journey_telemetry_event.sql",
        "classpath:db/migration/V42__create_reroute_proposal.sql",
        "classpath:db/migration/V43__add_reroute_proposal_accepted_candidate.sql",
        "classpath:db/migration/V50__add_reroute_proposal_origin.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("V42 재탐색 제안 표 스키마")
class JourneyRerouteProposalSchemaIT extends AbstractJourneyOutboxPostgresIT {

    @Autowired JdbcTemplate jdbcTemplate;

    private long userId;
    private long journeyId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM reroute_proposal");
        jdbcTemplate.update("DELETE FROM journey_session");
        userId = insertUser();
        journeyId = insertJourney(userId);
    }

    @Test
    @DisplayName("같은 환승에 살아 있는 제안은 하나뿐이다")
    void onlyOneActiveProposalPerTransfer() {
        insertProposal(0, "ACTIVE");

        assertThatThrownBy(() -> insertProposal(0, "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("앞선 제안을 만료로 바꾸면 같은 환승에 새 제안이 들어간다")
    void expiredProposalFreesTheSlot() {
        insertProposal(0, "ACTIVE");
        jdbcTemplate.update("UPDATE reroute_proposal SET status = 'EXPIRED'");

        // 전체 unique 였다면 여기서 실패한다. 이 단언이 이 파일의 존재 이유다.
        assertThatCode(() -> insertProposal(0, "ACTIVE")).doesNotThrowAnyException();

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reroute_proposal", Integer.class);
        assertThat(rows).as("만료 행은 지우지 않는다 - 늦은 수락이 404 인지 409 인지 갈라야 한다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("다른 환승은 동시에 살아 있을 수 있다")
    void differentTransfersCoexist() {
        insertProposal(0, "ACTIVE");

        assertThatCode(() -> insertProposal(1, "ACTIVE")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("모르는 kind·status 는 거절된다")
    void checkConstraintsRejectUnknownVocabulary() {
        assertThatThrownBy(() -> insertProposal(0, "ACTIVE", "SOMETHING_ELSE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertProposal(1, "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("만료 sweep 이 쓸 인덱스가 있다")
    void expiryIndexExists() {
        List<String> indexes = jdbcTemplate.queryForList("""
                select indexname from pg_indexes where tablename = 'reroute_proposal'
                """, String.class);

        assertThat(indexes).contains(
                "uq_reroute_proposal_active",
                "ix_reroute_proposal_journey",
                "ix_reroute_proposal_expiry");
    }

    private long insertUser() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (created_at, updated_at) VALUES (now(), now()) RETURNING id
                """, Long.class);
    }

    private long insertJourney(long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO journey_session
                    (created_at, updated_at, user_id, status, command_version, evaluation_version,
                     route_revision, current_leg_index, started_at, last_heartbeat_at,
                     guidance_lease_until, route_snapshot)
                VALUES (now(), now(), ?, 'ACTIVE', 1, 0, 1, 0, now(), now(),
                        now() + interval '3 minutes', '{}'::jsonb)
                RETURNING id
                """, Long.class, userId);
    }

    private void insertProposal(int transferIndex, String status) {
        insertProposal(transferIndex, status, "ROUTE_OPTION");
    }

    private void insertProposal(int transferIndex, String status, String kind) {
        jdbcTemplate.update("""
                INSERT INTO reroute_proposal
                    (created_at, updated_at, journey_id, user_id, route_revision, transfer_index,
                     kind, status, base_command_version, candidates,
                     snapshot_observed_at, expires_at)
                VALUES (now(), now(), ?, ?, 1, ?, ?, ?, 1,
                        '{"version":1,"candidates":[]}'::jsonb, now(), now() + interval '2 minutes')
                """, journeyId, userId, transferIndex, kind, status);
    }
}
