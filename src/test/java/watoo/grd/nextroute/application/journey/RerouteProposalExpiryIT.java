package watoo.grd.nextroute.application.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteContext;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.service.RerouteProposalExpiryService;
import watoo.grd.nextroute.application.journey.service.RerouteOriginResolver;
import watoo.grd.nextroute.application.journey.service.RerouteProposalTxService;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.testsupport.AbstractJourneyOutboxPostgresIT;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>아무도 API 를 부르지 않는 상황</b>을 만든다.
 *
 * <p>lazy 전이는 조회·생성·수락이 지나갈 때만 돈다. 앱이 죽거나 백그라운드로 들어가면 그 셋이
 * 모두 오지 않고, TTL 이 지난 행이 {@code ACTIVE} 로 남아 <b>같은 환승의 새 제안을 영영 막는다.</b>
 * 배치 sweep 이 그 자리를 여는지가 이 파일의 존재 이유다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, RerouteProposalExpiryService.class, RerouteProposalTxService.class,
        RerouteOriginResolver.class, JourneyRerouteMetrics.class, RerouteProposalExpiryIT.Config.class})
@Sql(scripts = {
        "classpath:db/it/journey-reset.sql",
        "classpath:db/migration/V35__create_journey.sql",
        "classpath:db/migration/V36__add_journey_transit_resolution.sql",
        "classpath:db/migration/V37__align_journey_navigation_contract.sql",
        "classpath:db/migration/V38__add_journey_last_progress_id.sql",
        "classpath:db/migration/V39__create_user_push_token.sql",
        "classpath:db/migration/V68__user_push_token_quiet_update.sql",
        "classpath:db/migration/V40__create_journey_notification.sql",
        "classpath:db/migration/V70__journey_notification_priority.sql",
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
        "classpath:db/migration/V63__journey_leg_guidance_info.sql",
        "classpath:db/migration/V65__journey_leg_alight_door_side.sql",
        "classpath:db/migration/V66__journey_session_evaluation_urgent.sql",
        "classpath:db/migration/V67__journey_reevaluation_trigger.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@TestPropertySource(properties = {
        "journey.reroute.expiry.batch-size=2",
        "journey.reroute.expiry.max-sweeps-per-tick=2"
})
@DisplayName("재탐색 제안 배치 만료")
class RerouteProposalExpiryIT extends AbstractJourneyOutboxPostgresIT {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    /** 한 tick 의 상한을 눈으로 셀 수 있게 작게 잡는다. */
    private static final int BATCH_SIZE = 2;
    private static final int MAX_SWEEPS = 2;

    @TestConfiguration
    static class Config {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObjectMapper objectMapper() {
            return MAPPER;
        }

        /**
         * 값은 {@code @TestPropertySource} 로 준다. 여기서 손으로 채워도
         * {@code ConfigurationPropertiesBindingPostProcessor} 가 {@code application.yaml} 값으로
         * 다시 덮어써, 상한을 작게 잡은 줄 알고도 기본값으로 도는 시험이 된다.
         */
        @Bean
        JourneyRerouteProperties journeyRerouteProperties() {
            return new JourneyRerouteProperties();
        }
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JourneySessionRepository sessionRepository;
    @Autowired RerouteProposalExpiryService expiryService;
    @Autowired RerouteProposalTxService txService;

    private long userId;
    private long journeyId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from reroute_proposal");
        jdbcTemplate.update("delete from transfer_connection");
        jdbcTemplate.update("delete from journey_leg");
        jdbcTemplate.update("delete from journey_session");
        jdbcTemplate.update("delete from users");
        userId = newUser();
        journeyId = newJourney(userId);
        // 저장 직전 재확인(R3)이 볼 환승. 없으면 store 가 stale-context 로 비켜 간다.
        jdbcTemplate.update("""
                insert into transfer_connection
                    (created_at, updated_at, journey_id, route_revision, transfer_index,
                     from_leg_index, to_leg_index, status)
                values (now(), now(), ?, 1, 0, 1, 2, 'AT_RISK')
                """, journeyId);
        // 그 환승이 가리키는 탑승 leg. 저장 경로가 여기서 원점을 만든다(stage5 2/2 — 원점 없이는
        // 저장하지 않는다). 노선은 일부러 비운다: 채우면 원점 해석이 bus_route_stop 을 읽는데
        // 이 IT 스키마에는 그 표가 없다.
        jdbcTemplate.update("""
                insert into journey_leg
                    (created_at, updated_at, journey_id, route_revision, leg_index, type, state,
                     start_stop_id)
                values (now(), now(), ?, 1, 2, 'WAIT_BUS', 'PENDING', 'ST-C')
                """, journeyId);
    }

    @Test
    @DisplayName("요청이 하나도 없어도 TTL 이 지난 제안을 접는다")
    void sweepExpiresWithoutAnyRequest() {
        insertProposal(NOW.minusSeconds(1));

        int expired = expiryService.sweep();

        assertThat(expired).isEqualTo(1);
        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("EXPIRED");
            assertThat(row.get("resolved_reason"))
                    .as("lazy 전이(ttl)와 갈라야 한다 - 이쪽이 대부분이면 사용자가 제안을 못 보고 있다")
                    .isEqualTo("ttl-sweep");
        });
    }

    @Test
    @DisplayName("접힌 뒤 그 여정에 새 제안이 들어간다")
    void sweepFreesTheSlot() {
        insertProposal(NOW.minusSeconds(1));
        expiryService.sweep();

        // lazy 만 있을 때 막히던 자리다. 앱이 죽어 있으면 이 삽입이 영영 안 된다.
        txService.store(new RerouteContext(journeyId, userId, 1, 1L, 0, NOW),
                RerouteProposalKind.REMAINING_PATH, List.of(candidate()), NOW, NOW.plusSeconds(120));

        assertThat(activeCount()).isEqualTo(1);
        assertThat(rows()).hasSize(2);
    }

    @Test
    @DisplayName("아직 만료 안 된 제안은 건드리지 않는다")
    void sweepLeavesLiveProposals() {
        insertProposal(NOW.plusSeconds(60));

        assertThat(expiryService.sweep()).isZero();
        assertThat(activeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 tick 은 batch × max-sweeps 를 넘지 않는다")
    void sweepRespectsTickBound() {
        // 여정마다 하나씩 심는다(stage5 — 살아 있는 제안은 여정당 하나라 한 여정에 여럿을 못
        // 넣는다). sweep 은 어차피 여정을 가리지 않고 만료된 행을 배치로 접는다.
        for (int i = 0; i < BATCH_SIZE * MAX_SWEEPS + 2; i++) {
            // 사용자당 ACTIVE 여정도 하나라(uq_journey_active_per_user) 사용자까지 새로 만든다.
            long owner = newUser();
            insertProposal(owner, newJourney(owner), NOW.minusSeconds(1));
        }

        int expired = expiryService.sweep();

        assertThat(expired)
                .as("상한이 없으면 밀린 만큼 한 tick 이 끝없이 돌아 다른 잡이 스케줄러를 못 쓴다")
                .isEqualTo(BATCH_SIZE * MAX_SWEEPS);
        assertThat(activeCount()).isEqualTo(2);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("서버가 알린 제안이 답 없이 만료되면 무응답으로 센다 — 이 경로가 본류다")
    void sweepCountsUnansweredAlerts() {
        insertProposal(NOW.minusSeconds(1));
        jdbcTemplate.update("update reroute_proposal set alerted = true");

        expiryService.sweep();

        assertThat(ignoredCount())
                .as("사용자가 앱을 안 열면 lazy 전이가 한 번도 오지 않는다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("사용자가 직접 요청한 제안의 만료는 무응답이 아니다")
    void sweepIgnoresPullProposals() {
        insertProposal(NOW.minusSeconds(1));

        expiryService.sweep();

        assertThat(activeCount()).isZero();
        assertThat(ignoredCount()).isZero();
    }

    private int ignoredCount() {
        return jdbcTemplate.queryForObject(
                "select reroute_ignored_count from journey_session where id = ?",
                Integer.class, journeyId);
    }

    private void insertProposal(Instant expiresAt) {
        insertProposal(userId, journeyId, expiresAt);
    }

    private void insertProposal(long userId, long journeyId, Instant expiresAt) {
        jdbcTemplate.update("""
                insert into reroute_proposal
                    (created_at, updated_at, journey_id, user_id, route_revision,
                     kind, status, base_command_version, candidates,
                     snapshot_observed_at, expires_at)
                values (now(), now(), ?, ?, 1, 'REMAINING_PATH', 'ACTIVE', 1,
                        '{"version":1,"candidates":[]}'::jsonb, ?, ?)
                """, journeyId, userId,
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(expiresAt));
    }

    private RerouteCandidate candidate() {
        return new RerouteCandidate(null, RerouteProposalKind.REMAINING_PATH,
                new RerouteCandidateApply.RemainingPathApply(1,
                        new watoo.grd.nextroute.application.route.dto.PathResult(
                                1, null, List.of(), List.of())),
                new RerouteCandidateDisplay(NOW.plusSeconds(1_200), 1_200, 120, 1, 300,
                        "REALTIME", "HIGH", NOW.minusSeconds(20), List.of("370 버스")));
    }

    private long newUser() {
        return jdbcTemplate.queryForObject(
                "insert into users (created_at, updated_at) values (now(), now()) returning id",
                Long.class);
    }

    private long newJourney(long ownerId) {
        return sessionRepository.saveAndFlush(JourneySession.builder()
                .userId(ownerId)
                .startedAt(NOW)
                .guidanceLeaseUntil(NOW.plusSeconds(180))
                .routeSnapshot(MAPPER.createObjectNode().put("pathType", 1))
                .build()).getId();
    }

    private List<Map<String, Object>> rows() {
        return jdbcTemplate.queryForList("select * from reroute_proposal order by id");
    }

    private int activeCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from reroute_proposal where status = 'ACTIVE'", Integer.class);
    }
}
