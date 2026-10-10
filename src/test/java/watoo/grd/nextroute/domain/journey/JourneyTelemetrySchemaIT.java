package watoo.grd.nextroute.domain.journey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.testsupport.AbstractJourneyOutboxPostgresIT;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V41 이 만든 표의 모양을 실 PostgreSQL 로 고정한다.
 *
 * <p>여기서 지키려는 것 둘.
 * <ul>
 *   <li>유일성이 <b>사용자별</b>이다. 전역으로 두면 기기를 물려받아 계정이 바뀔 때 앞선 사용자의
 *       키가 새 사용자의 이벤트를 조용히 삼킨다</li>
 *   <li>{@code received_at} 에 인덱스가 있다. 30일 보관 삭제가 이 컬럼으로 돌기 때문이다 —
 *       기기 시각으로 지우면 시계를 미래로 돌린 기기의 데이터가 영원히 남는다</li>
 * </ul>
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
        "classpath:db/migration/V53__add_reroute_alert_state.sql",
        "classpath:db/migration/V59__journey_session_kind.sql",
        "classpath:db/migration/V63__journey_leg_guidance_info.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("V41 계측 표 스키마")
class JourneyTelemetrySchemaIT extends AbstractJourneyOutboxPostgresIT {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("유일성은 사용자별이다")
    void uniqueIsScopedToUser() {
        List<String> definition = jdbcTemplate.queryForList("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conrelid = 'journey_telemetry_event'::regclass and contype = 'u'
                """, String.class);

        assertThat(definition).singleElement()
                .as("전역 유일이면 계정 교체 시 남의 키가 내 이벤트를 삼킨다")
                .isEqualTo("UNIQUE (user_id, telemetry_id)");
    }

    @Test
    @DisplayName("보관 삭제가 쓸 인덱스가 있다")
    void retentionIndexExists() {
        List<String> indexes = jdbcTemplate.queryForList("""
                select indexname from pg_indexes where tablename = 'journey_telemetry_event'
                """, String.class);

        assertThat(indexes).contains(
                "ix_journey_telemetry_event_received",
                "ix_journey_telemetry_event_journey",
                "ix_journey_telemetry_event_type_time");
    }

    @Test
    @DisplayName("알림에 FK 를 걸지 않는다 — 알림 보관 정책이 계측 쓰기를 막으면 안 된다")
    void noForeignKeyToNotification() {
        List<String> fks = jdbcTemplate.queryForList("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conrelid = 'journey_telemetry_event'::regclass and contype = 'f'
                """, String.class);

        assertThat(fks).noneMatch(fk -> fk.contains("journey_notification"));
    }
}
