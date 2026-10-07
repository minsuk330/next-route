package watoo.grd.nextroute.application.journey.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.testsupport.AbstractJourneyOutboxPostgresIT;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 워커가 여럿이어도 한 알림은 정확히 한 번만 집힌다.
 *
 * <p>겹치면 같은 알림이 두 번 나간다. {@code FOR UPDATE ... SKIP LOCKED} 가 막아야 하는 지점이고,
 * 그 성질은 동시에 돌려 봐야만 드러난다.
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
        "classpath:db/migration/V53__add_reroute_alert_state.sql",
        "classpath:db/migration/V59__journey_session_kind.sql",
        "classpath:db/migration/V61__journey_leg_vehicle.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("동시 claim 은 알림을 두 번 집지 않는다")
class JourneyNotificationAtomicClaimIT extends AbstractJourneyOutboxPostgresIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ROWS = 40;
    private static final int WORKERS = 4;

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JourneySessionRepository sessionRepository;
    @Autowired JourneyNotificationRepository notificationRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private long journeyId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from journey_notification");
        jdbcTemplate.update("delete from journey_session");
        jdbcTemplate.update("delete from users");
        long userId = jdbcTemplate.queryForObject(
                "insert into users (created_at, updated_at) values (now(), now()) returning id",
                Long.class);
        journeyId = sessionRepository.saveAndFlush(JourneySession.builder()
                .userId(userId)
                .startedAt(Instant.now())
                .guidanceLeaseUntil(Instant.now().plusSeconds(180))
                .routeSnapshot(MAPPER.createObjectNode().put("pathType", 1))
                .build()).getId();
    }

    @Test
    @DisplayName("네 워커가 동시에 집어도 각 행은 한 번씩만 나온다")
    void concurrentClaimsAreDisjoint() throws Exception {
        for (int i = 0; i < ROWS; i++) {
            enqueue();
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Callable<List<Long>> claim = () -> tx.execute(status -> notificationRepository
                .claimBatchForProcessing(Instant.now(), 10, LocalDateTime.now()));

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        List<Long> all = new ArrayList<>();
        try {
            List<Callable<List<Long>>> tasks = Collections.nCopies(WORKERS, claim);
            for (Future<List<Long>> future : pool.invokeAll(tasks)) {
                all.addAll(future.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(all)
                .as("겹치면 같은 알림이 두 번 나간다")
                .doesNotHaveDuplicates()
                .hasSize(WORKERS * 10);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from journey_notification where status = 'PROCESSING'", Integer.class))
                .isEqualTo(WORKERS * 10);
    }

    private void enqueue() {
        String key = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                insert into journey_notification
                    (created_at, updated_at, journey_id, user_id, route_revision,
                     command_version, evaluation_version, scope_type, scope_id, checkpoint,
                     channel, logical_checkpoint_key, delivery_key, status,
                     scheduled_at, expires_at, attempt_count, requeue_count)
                select now(), now(), s.id, s.user_id, s.route_revision, s.command_version,
                       s.evaluation_version, 'TRANSFER', 0, 'TRANSFER_RISK_AT_RISK', 'PUSH',
                       ?, ? || ':PUSH', 'PENDING', ?, ?, 0, 0
                from journey_session s where s.id = ?
                """, key, key, Timestamp.from(now.minusSeconds(1)),
                Timestamp.from(now.plusSeconds(600)), journeyId);
    }
}
