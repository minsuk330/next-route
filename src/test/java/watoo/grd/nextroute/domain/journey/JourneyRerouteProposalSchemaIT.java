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
        "classpath:db/migration/V50__add_reroute_proposal_origin.sql",
        "classpath:db/migration/V51__reroute_proposal_journey_active_unique.sql",
        "classpath:db/migration/V52__drop_reroute_proposal_transfer_index.sql",
        "classpath:db/migration/V53__add_reroute_alert_state.sql",
        "classpath:db/migration/V54__add_reroute_proposal_alerted.sql",
        "classpath:db/migration/V57__reroute_board.sql",
        "classpath:db/migration/V58__reroute_refresh_claim.sql",
        "classpath:db/migration/V59__journey_session_kind.sql",
        "classpath:db/migration/V63__journey_leg_guidance_info.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("재탐색 제안 표 스키마(V42→V52)")
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
    @DisplayName("여정에 살아 있는 제안은 하나뿐이다")
    void onlyOneActiveProposalPerJourney() {
        insertProposal("ACTIVE");

        // stage5 이전에는 <b>환승당</b> 하나였다(환승 번호가 다르면 같은 여정에도 공존). 원점이
        // leg 경계를 넘어 주행 중 정류장까지 가리키게 되면서 그 단위가 성립하지 않는다 — 어느
        // 것이 지금 제안인지 사용자가 알 수 없고, 수락은 어차피 여정을 끝낸다.
        assertThatThrownBy(() -> insertProposal("ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("앞선 제안을 접으면 자리가 비어 새 제안이 들어간다")
    void resolvedProposalFreesTheSlot() {
        insertProposal("ACTIVE");
        jdbcTemplate.update("UPDATE reroute_proposal SET status = 'EXPIRED'");

        // 전체 unique 였다면 여기서 실패한다. 이 단언이 이 파일의 존재 이유다.
        assertThatCode(() -> insertProposal("ACTIVE")).doesNotThrowAnyException();

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reroute_proposal", Integer.class);
        assertThat(rows).as("만료 행은 지우지 않는다 - 늦은 수락이 404 인지 409 인지 갈라야 한다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("옛 주소(transfer_index)는 컬럼째 사라졌다 — 주소는 origin 하나다")
    void transferIndexColumnIsGone() {
        List<String> columns = jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                 where table_name = 'reroute_proposal'
                """, String.class);

        assertThat(columns).doesNotContain("transfer_index").contains("origin");
    }

    @Test
    @DisplayName("모르는 kind·status 는 거절된다")
    void checkConstraintsRejectUnknownVocabulary() {
        assertThatThrownBy(() -> insertProposal("ACTIVE", "SOMETHING_ELSE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertProposal("PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("사용자의 응답도 상태 어휘다 — 거절·무시가 통과한다(V54)")
    void decisionStatusesAreAccepted() {
        insertProposal("REJECTED");
        insertProposal("DISMISSED");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from reroute_proposal where status in ('REJECTED','DISMISSED')",
                Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("알림 상태 칸이 여정과 제안에 있다(V53·V54)")
    void alertStateColumnsExist() {
        assertThat(columnsOf("journey_session"))
                .contains("reroute_terminated", "reroute_alert_opt_in", "reroute_ignored_count");
        assertThat(columnsOf("reroute_proposal"))
                .as("사용자가 직접 요청한 제안은 무응답으로 세지 않는다")
                .contains("alerted");
    }

    private List<String> columnsOf(String table) {
        return jdbcTemplate.queryForList("""
                select column_name from information_schema.columns where table_name = ?
                """, String.class, table);
    }

    @Test
    @DisplayName("만료 sweep 이 쓸 인덱스가 있다")
    void expiryIndexExists() {
        List<String> indexes = jdbcTemplate.queryForList("""
                select indexname from pg_indexes where tablename = 'reroute_proposal'
                """, String.class);

        assertThat(indexes).as("옛 주소의 인덱스는 컬럼과 함께 사라졌다")
                .doesNotContain("uq_reroute_proposal_active");
        assertThat(indexes).contains(
                "uq_reroute_proposal_journey_active",
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

    private void insertProposal(String status) {
        insertProposal(status, "REMAINING_PATH");
    }

    private void insertProposal(String status, String kind) {
        jdbcTemplate.update("""
                INSERT INTO reroute_proposal
                    (created_at, updated_at, journey_id, user_id, route_revision,
                     kind, status, base_command_version, candidates,
                     snapshot_observed_at, expires_at)
                VALUES (now(), now(), ?, ?, 1, ?, ?, 1,
                        '{"version":1,"candidates":[]}'::jsonb, now(), now() + interval '2 minutes')
                """, journeyId, userId, kind, status);
    }
}
