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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteContext;
import watoo.grd.nextroute.application.journey.dto.RerouteProposalView;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 제안 저장의 계약.
 *
 * <p>여기서 지키려는 것은 <b>자리가 막히지 않는 것</b>이다. 부분 unique 는 같은 환승에 살아 있는
 * 제안을 하나로 묶는데, 만료·개정본 변경으로 무의미해진 행이 {@code ACTIVE} 로 남으면 그 자리
 * 때문에 새 제안이 영영 안 만들어진다. 저장 한 번이 "만료 전이 → supersede → insert" 를 한
 * 트랜잭션으로 끝내는 이유가 그것이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, RerouteProposalTxService.class, RerouteOriginResolver.class,
        JourneyRerouteMetrics.class, RerouteProposalStoreIT.Config.class})
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
        "classpath:db/migration/V52__drop_reroute_proposal_transfer_index.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("재탐색 제안 저장")
class RerouteProposalStoreIT extends AbstractJourneyOutboxPostgresIT {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

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

        @Bean
        JourneyRerouteProperties journeyRerouteProperties() {
            return new JourneyRerouteProperties();
        }
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JourneySessionRepository sessionRepository;
    @Autowired RerouteProposalTxService txService;

    private long userId;
    private long journeyId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from reroute_proposal");
        jdbcTemplate.update("delete from transfer_connection");
        // journey_leg 가 여정을 참조한다. 원점 해석이 이 표를 읽으므로 여기서도 비운다.
        jdbcTemplate.update("delete from journey_leg");
        jdbcTemplate.update("delete from journey_session");
        jdbcTemplate.update("delete from users");
        userId = newUser();
        journeyId = newJourney(userId);
        newTransfer(0, 2);
        // 저장 경로가 이 leg 에서 원점을 만든다(stage5 2/2 — 원점 없이는 저장하지 않는다).
        newBoardingLeg(2, "ST-C");
    }

    // ── 자리가 막히지 않는가 ────────────────────────────────────────────────

    @Test
    @DisplayName("만료된 제안이 남아 있어도 새 제안이 들어간다")
    void staleProposalDoesNotBlockNewOne() {
        txService.store(context(1, 0), RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate()), NOW, NOW.minusSeconds(1));

        RerouteProposalView stored = txService.store(context(1, 0),
                RerouteProposalKind.ROUTE_OPTION, List.of(candidate()), NOW, NOW.plusSeconds(120))
                .orElseThrow();

        assertThat(stored.proposalId()).isNotNull();
        assertThat(activeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("개정본이 오르면 이전 개정본 제안이 route-changed 로 정리된다")
    void revisionBumpResolvesOldProposals() {
        txService.store(context(1, 0), RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate()), NOW, NOW.plusSeconds(120));

        txService.resolveStale(journeyId, 2, NOW);

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.get("status"))
                    .as("만료 시각이 남아 있어도 이전 개정본의 제안은 무의미하다")
                    .isEqualTo("SUPERSEDED");
            assertThat(row.get("resolved_reason")).isEqualTo("route-changed");
        });
    }

    @Test
    @DisplayName("같은 환승에 새 제안이 오면 앞선 것이 replaced 로 밀려난다")
    void newProposalReplacesPrevious() {
        txService.store(context(1, 0), RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate()), NOW, NOW.plusSeconds(120));

        txService.store(context(1, 0), RerouteProposalKind.REMAINING_PATH,
                List.of(candidate()), NOW, NOW.plusSeconds(120));

        assertThat(rows()).hasSize(2);
        assertThat(rows().get(0).get("resolved_reason")).isEqualTo("replaced");
        assertThat(activeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("지나갔는지는 원점의 leg 번호로 본다 — 환승 행을 다시 읽지 않는다")
    void passedCheckUsesTheOriginWhenResolved() {
        assertThat(txService.store(context(1, 0), RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate()), NOW, NOW.plusSeconds(120))).isPresent();
        assertThat(lastOriginLegIndex()).isEqualTo(2);

        // 그 leg 를 지나면 저장하지 않는다. 기준이 원점이라는 것은 주행 중 정류장 원점
        // (대응하는 환승 행이 아예 없다)이 생기는 PR-2·PR-4 에서 실제로 갈린다.
        jdbcTemplate.update("update journey_session set current_leg_index = 3 where id = ?",
                journeyId);

        assertThat(txService.store(context(1, 0), RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate()), NOW, NOW.plusSeconds(120))).isEmpty();
    }

    @Test
    @DisplayName("원점을 못 풀면 저장하지 않는다 — 주소 없는 제안은 만들지 않는다")
    void proposalWithoutOriginIsNotStored() {
        // 환승이 가리키는 탑승 leg 가 없으면 원점을 만들 수 없다. 옛 계약에서는
        // transfer_index 만으로 저장됐지만, 그 컬럼이 사라진 지금 그런 행은 "어디에 대한
        // 제안인지"를 영영 말할 수 없다.
        jdbcTemplate.update("delete from journey_leg");

        assertThat(txService.store(context(1, 0), RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate()), NOW, NOW.plusSeconds(120))).isEmpty();
        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("다른 환승의 제안도 밀려난다 — 살아 있는 제안은 여정당 하나다(stage5)")
    void newProposalReplacesOtherTransfersToo() {
        newTransfer(1, 4);
        newBoardingLeg(4, "ST-E");
        txService.store(context(1, 0), RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate()), NOW, NOW.plusSeconds(120));

        // stage5 이전에는 둘이 공존했다(환승당 하나). 원점이 leg 경계를 넘어가면서 그 단위가
        // 성립하지 않는다 — 자리를 비우는 규칙과 부분 unique 인덱스는 짝이라 함께 좁혔다.
        txService.store(context(1, 1), RerouteProposalKind.REMAINING_PATH,
                List.of(candidate()), NOW, NOW.plusSeconds(120));

        assertThat(rows()).hasSize(2);
        assertThat(rows().get(0).get("resolved_reason")).isEqualTo("replaced");
        assertThat(activeCount()).isEqualTo(1);
        // 남은 행은 두 번째 원점(leg 4)을 가리킨다. 옛 transfer_index 는 컬럼째 사라졌고
        // 이 값이 그 자리를 대신한다(stage5 2/2).
        assertThat(lastOriginLegIndex()).isEqualTo(4);
    }

    @Test
    @DisplayName("정리된 행은 지우지 않는다")
    void resolvedRowsAreKept() {
        txService.store(context(1, 0), RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate()), NOW, NOW.minusSeconds(1));
        txService.store(context(1, 0), RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate()), NOW, NOW.plusSeconds(120));

        assertThat(rows())
                .as("지우면 늦게 온 수락이 '없음'인지 '만료됨'인지 구별되지 않는다")
                .hasSize(2);
    }

    // ── 후보 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("후보가 상한을 넘으면 잘라 저장한다")
    void candidatesAreTruncated() {
        RerouteProposalView stored = txService.store(context(1, 0),
                RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate(), candidate(), candidate(), candidate()),
                NOW, NOW.plusSeconds(120)).orElseThrow();

        assertThat(stored.candidates()).hasSize(3);
        assertThat(txService.findActive(journeyId)).get()
                .satisfies(view -> assertThat(view.candidates()).hasSize(3));
    }

    @Test
    @DisplayName("candidateId 는 저장 시점에 서버가 부여한다")
    void candidateIdsAreAssignedOnStore() {
        RerouteProposalView stored = txService.store(context(1, 0),
                RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate("provider-가-넣은-값"), candidate("같은-값")),
                NOW, NOW.plusSeconds(120)).orElseThrow();

        assertThat(stored.candidates()).extracting(RerouteCandidate::candidateId)
                .as("순번이나 provider 가 준 값을 믿으면 다른 경로가 적용될 수 있다")
                .doesNotContain("provider-가-넣은-값", "같은-값")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("적용 재료가 왕복 뒤에도 남아 있다")
    void applySurvivesStorage() {
        txService.store(context(1, 0), RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate()), NOW, NOW.plusSeconds(120));

        assertThat(txService.findActive(journeyId)).get()
                .satisfies(view -> assertThat(view.candidates()).singleElement()
                        .satisfies(candidate -> assertThat(candidate.apply())
                                .isEqualTo(new RerouteCandidateApply.RouteOptionApply(
                                        1, 2, "BL-370", "UP"))));
    }

    // ── 저장 직전 재확인 (R3) ───────────────────────────────────────────────

    @Test
    @DisplayName("후보를 만드는 사이 여정이 끝났으면 저장하지 않는다")
    void endedJourneyIsNotStored() {
        jdbcTemplate.update("update journey_session set status = 'COMPLETED' where id = ?",
                journeyId);

        assertThat(txService.store(context(1, 0), RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate()), NOW, NOW.plusSeconds(120)))
                .as("끝난 여정에 살아 있는 제안이 남는다")
                .isEmpty();
        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("그 사이 사용자 명령이 반영됐으면 저장하지 않는다")
    void bumpedCommandVersionIsNotStored() {
        // 후보를 만드는 동안 사용자가 탑승·하차를 알렸다. 지금 제안은 그 전의 판단이다.
        jdbcTemplate.update("update journey_session set command_version = 2 where id = ?",
                journeyId);

        assertThat(txService.store(context(1, 0), RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate()), NOW, NOW.plusSeconds(120))).isEmpty();
    }

    @Test
    @DisplayName("그 사이 경로가 바뀌었으면 저장하지 않는다")
    void bumpedRouteRevisionIsNotStored() {
        jdbcTemplate.update("update journey_session set route_revision = 2 where id = ?",
                journeyId);

        assertThat(txService.store(context(1, 0), RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate()), NOW, NOW.plusSeconds(120)))
                .as("후보의 legIndex 가 다른 단계를 가리키게 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("그 사이 그 환승을 지났으면 저장하지 않는다")
    void passedTransferIsNotStored() {
        jdbcTemplate.update("update journey_session set current_leg_index = 4 where id = ?",
                journeyId);

        assertThat(txService.store(context(1, 0), RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate()), NOW, NOW.plusSeconds(120)))
                .as("사용자가 할 수 있는 일이 없는 제안이 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("진행 중인 환승은 아직 지나지 않은 것이다")
    void currentTransferIsStillStored() {
        jdbcTemplate.update("update journey_session set current_leg_index = 2 where id = ?",
                journeyId);

        assertThat(txService.store(context(1, 0), RerouteProposalKind.ROUTE_OPTION,
                List.of(candidate()), NOW, NOW.plusSeconds(120))).isPresent();
    }

    // ── 동시성 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("동시에 두 요청이 와도 살아 있는 제안은 하나다")
    void concurrentStoresLeaveOneActive() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = List.of(
                    pool.submit(() -> attemptStore(start)),
                    pool.submit(() -> attemptStore(start)));
            start.countDown();

            long succeeded = results.stream().filter(RerouteProposalStoreIT::joined).count();

            assertThat(succeeded).as("적어도 한 쪽은 성공해야 한다").isPositive();
            assertThat(activeCount())
                    .as("겹쳐 들어와도 부분 unique 가 살아 있는 제안을 하나로 묶는다")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 실패해도 <b>재시도 가능한 형태</b>여야 한다 — 부분 unique 위반은 "다른 쪽이 먼저 만들었다"는
     * 뜻이라 다시 부르면 된다. 다른 예외라면 그 자체가 결함이므로 그대로 터뜨린다.
     */
    private boolean attemptStore(CountDownLatch start) throws Exception {
        start.await();
        try {
            txService.store(context(1, 0), RerouteProposalKind.ROUTE_OPTION,
                    List.of(candidate()), NOW, NOW.plusSeconds(120));
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException
                 | org.springframework.dao.ConcurrencyFailureException e) {
            return false;
        }
    }

    private static boolean joined(Future<Boolean> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private RerouteContext context(int routeRevision, int transferIndex) {
        return new RerouteContext(journeyId, userId, routeRevision, 1L, transferIndex, NOW);
    }

    private RerouteCandidate candidate() {
        return candidate(null);
    }

    private RerouteCandidate candidate(String candidateId) {
        return new RerouteCandidate(candidateId, RerouteProposalKind.ROUTE_OPTION,
                new RerouteCandidateApply.RouteOptionApply(1, 2, "BL-370", "UP"),
                new RerouteCandidateDisplay(NOW.plusSeconds(1_200), 1_200, 180, 1, 300,
                        "REALTIME", "HIGH", NOW.minusSeconds(20), List.of("370 버스")));
    }

    private long newUser() {
        return jdbcTemplate.queryForObject(
                "insert into users (created_at, updated_at) values (now(), now()) returning id",
                Long.class);
    }

    /**
     * 저장 직전 재확인이 볼 환승 하나(R3). 없으면 저장이 {@code stale-context} 로 비켜 간다 —
     * 제안은 언제나 살아 있는 환승 하나를 가리킨다.
     */
    private void newTransfer(int transferIndex, int toLegIndex) {
        jdbcTemplate.update("""
                insert into transfer_connection
                    (created_at, updated_at, journey_id, route_revision, transfer_index,
                     from_leg_index, to_leg_index, status)
                values (now(), now(), ?, 1, ?, ?, ?, 'AT_RISK')
                """, journeyId, transferIndex, toLegIndex - 1, toLegIndex);
    }

    /** 환승이 가리키는 탑승 leg. 원점 해석이 이 행에서 정류장을 읽는다. */
    private void newBoardingLeg(int legIndex, String startStopId) {
        jdbcTemplate.update("""
                insert into journey_leg
                    (created_at, updated_at, journey_id, route_revision, leg_index, type, state,
                     start_stop_id)
                values (now(), now(), ?, 1, ?, 'WAIT_BUS', 'PENDING', ?)
                """, journeyId, legIndex, startStopId);
    }

    private Integer lastOriginLegIndex() {
        return jdbcTemplate.queryForObject(
                "select (origin -> 'origin' ->> 'legIndex')::int from reroute_proposal"
                        + " where status = 'ACTIVE'", Integer.class);
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
