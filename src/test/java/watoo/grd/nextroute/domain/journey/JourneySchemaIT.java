package watoo.grd.nextroute.domain.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneyEndReason;
import watoo.grd.nextroute.domain.journey.entity.JourneyEvent;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevision;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevisionStatus;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyEventRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyRevisionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V35~V37이 만든 스키마와 Journey 엔티티 매핑을 실 PostgreSQL 로 확인한다.
 *
 * <p>스키마를 테스트용으로 복제하지 않고 <b>실제 마이그레이션 파일을 그대로 실행</b>한다
 * ({@code @Sql}). 복제본을 두면 마이그레이션만 고치고 테스트는 통과하는 상태가 생긴다.
 *
 * <p>여기서 고정하는 것은 두 가지다.
 * <ul>
 *   <li>DB 가 소유한 불변조건 — 사람당 ACTIVE 여정 하나, 여정당 ACTIVE 개정본 하나, 명령 중복 금지</li>
 *   <li>엔티티↔칼럼 매핑 — jsonb 가 문자열이 아닌 객체로 오가는지, 시각이 그대로 돌아오는지</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaConfig.class)
@Sql(scripts = {
        "classpath:db/migration/V35__create_journey.sql",
        "classpath:db/migration/V36__add_journey_transit_resolution.sql",
        "classpath:db/migration/V37__align_journey_navigation_contract.sql",
        "classpath:db/migration/V38__add_journey_last_progress_id.sql",
        "classpath:db/migration/V39__create_user_push_token.sql",
        "classpath:db/migration/V68__user_push_token_quiet_update.sql",
        "classpath:db/migration/V40__create_journey_notification.sql",
        "classpath:db/migration/V70__journey_notification_priority.sql",
        "classpath:db/migration/V53__add_reroute_alert_state.sql",
        "classpath:db/migration/V59__journey_session_kind.sql",
        "classpath:db/migration/V63__journey_leg_guidance_info.sql",
        "classpath:db/migration/V65__journey_leg_alight_door_side.sql",
        "classpath:db/migration/V71__journey_leg_train_kind.sql",
        "classpath:db/migration/V66__journey_session_evaluation_urgent.sql",
        "classpath:db/migration/V67__journey_reevaluation_trigger.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class JourneySchemaIT {

    /**
     * {@code @Testcontainers} 확장 대신 정적 초기화로 띄운다.
     *
     * <p>{@code @Sql(BEFORE_TEST_CLASS)} 가 확장보다 먼저 컨텍스트를 만들기 때문에, 확장에
     * 시작을 맡기면 datasource 를 물을 때 아직 컨테이너가 없다.
     */
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
                    .withInitScript("db/it/journey-it-schema.sql");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.flyway.enabled", () -> "false");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired UserRepository userRepository;
    @Autowired JourneySessionRepository sessionRepository;
    @Autowired JourneyLegRepository legRepository;
    @Autowired JourneyLegRouteOptionRepository routeOptionRepository;
    @Autowired TransferConnectionRepository transferRepository;
    @Autowired JourneyEventRepository eventRepository;
    @Autowired JourneyRevisionRepository revisionRepository;

    private long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from journey_event");
        jdbcTemplate.update("delete from journey_revision");
        jdbcTemplate.update("delete from transfer_connection");
        jdbcTemplate.update("update journey_leg set selected_route_option_id = null");
        jdbcTemplate.update("delete from journey_leg_route_option");
        jdbcTemplate.update("delete from journey_leg");
        jdbcTemplate.update("delete from journey_notification");
        jdbcTemplate.update("delete from journey_session");
        jdbcTemplate.update("delete from users");
        userId = jdbcTemplate.queryForObject(
                "insert into users (created_at, updated_at) values (now(), now()) returning id",
                Long.class);
    }

    private JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JourneySession newSession(Instant startedAt) {
        return JourneySession.builder()
                .userId(userId)
                .startedAt(startedAt)
                .guidanceLeaseUntil(startedAt.plusSeconds(180))
                .routeSnapshot(json("{\"pathType\":1,\"subPath\":[]}"))
                .destinationSummary(json("{\"name\":\"집\"}"))
                .build();
    }

    // ── 스키마 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("V35 가 표 6개를 만든다")
    void tables_created() {
        List<String> tables = jdbcTemplate.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'public' and table_name like 'journey%'
                   or table_schema = 'public' and table_name = 'transfer_connection'
                """, String.class);

        assertThat(tables).contains(
                "journey_session", "journey_leg", "journey_leg_route_option",
                "transfer_connection", "journey_event", "journey_revision");
    }

    @Test
    @DisplayName("값 목록을 지키는 CHECK 제약이 걸려 있다")
    void check_constraints_exist() {
        List<String> checks = jdbcTemplate.queryForList(
                "select conname from pg_constraint where contype = 'c'", String.class);

        assertThat(checks).contains(
                "journey_session_status_check",
                "journey_end_reason_check",
                "journey_command_version_check",
                "journey_evaluation_version_check",
                "journey_route_revision_check",
                "journey_current_leg_index_check",
                "journey_leg_type_check",
                "journey_leg_state_check",
                "transfer_connection_status_check",
                "journey_event_result_check",
                "journey_revision_status_check");
    }

    @Test
    @DisplayName("불변조건을 소유한 부분 유일 인덱스가 실제로 걸려 있다")
    void partial_unique_indexes_exist() {
        assertThat(indexDefinition("uq_journey_active_per_user"))
                .contains("UNIQUE").contains("journey_session").contains("WHERE");
        assertThat(indexDefinition("uq_journey_active_revision"))
                .contains("UNIQUE").contains("journey_revision").contains("WHERE");
        assertThat(indexDefinition("uq_journey_leg_index"))
                .contains("UNIQUE").contains("route_revision").contains("leg_index");
        assertThat(indexDefinition("uq_journey_leg_route_option"))
                .contains("UNIQUE").contains("option_index");
        assertThat(indexDefinition("uq_transfer_connection_index"))
                .contains("UNIQUE").contains("transfer_index");
        assertThat(indexDefinition("uq_journey_event_id")).contains("UNIQUE");
    }

    private String indexDefinition(String indexName) {
        return jdbcTemplate.queryForObject(
                "select indexdef from pg_indexes where indexname = ?", String.class, indexName);
    }

    @Test
    @DisplayName("값 목록에 없는 상태는 DB 가 거부한다")
    void unknown_status_rejected() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into journey_session
                    (created_at, updated_at, user_id, status, started_at,
                     last_heartbeat_at, guidance_lease_until, route_snapshot)
                values (now(), now(), ?, 'PAUSED', now(), now(), now(), '{}')
                """, userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── 매핑 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("경로 원본이 문자열이 아니라 JSON 객체로 저장되고 그대로 돌아온다")
    void routeSnapshot_roundTrips_asJsonObject() {
        JourneySession saved = sessionRepository.saveAndFlush(newSession(Instant.now()));

        String storedType = jdbcTemplate.queryForObject(
                "select jsonb_typeof(route_snapshot) from journey_session where id = ?",
                String.class, saved.getId());
        assertThat(storedType).isEqualTo("object");

        JourneySession reloaded = sessionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getRouteSnapshot().isObject()).isTrue();
        assertThat(reloaded.getRouteSnapshot().get("pathType").asInt()).isEqualTo(1);
        assertThat(reloaded.getDestinationSummary().get("name").asText()).isEqualTo("집");
    }

    @Test
    @DisplayName("지하철 보호 lease가 nullable 절대시각으로 저장된다")
    void subwayProtection_roundTrips() {
        Instant protection = Instant.now().plus(20, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        JourneySession session = newSession(Instant.now());
        session.protectSubwayUntil(protection);

        JourneySession saved = sessionRepository.saveAndFlush(session);

        assertThat(sessionRepository.findById(saved.getId()).orElseThrow()
                .getSubwayOfflineProtectionUntil()).isEqualTo(protection);
    }

    @Test
    @DisplayName("경로 원본은 수정해도 DB 에 반영되지 않는다")
    void routeSnapshot_isImmutable() {
        JourneySession saved = sessionRepository.saveAndFlush(newSession(Instant.now()));

        ReflectionTestUtils.setField(saved, "routeSnapshot", json("{\"pathType\":99}"));
        ReflectionTestUtils.setField(saved, "destinationSummary", json("{\"name\":\"회사\"}"));
        sessionRepository.saveAndFlush(saved);

        JourneySession reloaded = sessionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getRouteSnapshot().get("pathType").asInt())
                .as("경로 원본은 updatable=false 라 갱신 대상에서 빠진다")
                .isEqualTo(1);
        assertThat(reloaded.getDestinationSummary().get("name").asText())
                .as("같은 update 문에서 다른 칼럼은 정상 갱신된다")
                .isEqualTo("회사");
    }

    @Test
    @DisplayName("시작 상태는 1 / 0 / 1 이고 첫 단계를 가리킨다")
    void initialVersions() {
        JourneySession saved = sessionRepository.saveAndFlush(newSession(Instant.now()));

        assertThat(saved.getStatus()).isEqualTo(JourneyStatus.ACTIVE);
        assertThat(saved.getCommandVersion()).isEqualTo(1L);
        assertThat(saved.getEvaluationVersion()).isEqualTo(0L);
        assertThat(saved.getRouteRevision()).isEqualTo(1);
        assertThat(saved.getCurrentLegIndex()).isZero();
    }

    @Test
    @DisplayName("절대시각은 timestamptz 로 오가며 값이 변하지 않는다")
    void instant_roundTrips() {
        Instant startedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        JourneySession saved = sessionRepository.saveAndFlush(newSession(startedAt));

        JourneySession reloaded = sessionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStartedAt()).isEqualTo(startedAt);
        assertThat(reloaded.getLastHeartbeatAt()).isEqualTo(startedAt);
        assertThat(reloaded.getGuidanceLeaseUntil()).isEqualTo(startedAt.plusSeconds(180));
    }

    @Test
    @DisplayName("감사 칼럼은 auditing 이 채운다")
    void auditColumns_filled() {
        JourneySession saved = sessionRepository.saveAndFlush(newSession(Instant.now()));

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("V71 열차 종류가 단계 안내 값과 함께 저장·조회된다(subway-express S2, null 은 null 그대로)")
    void trainKind_roundTrips() {
        JourneySession session = sessionRepository.saveAndFlush(newSession(Instant.now()));
        JourneyLeg express = legRepository.saveAndFlush(JourneyLeg.builder()
                .journeyId(session.getId()).routeRevision(1).legIndex(0)
                .type(JourneyLegType.RIDE_SUBWAY).state(JourneyLegState.ACTIVE)
                .startName("가양").endName("염창").plannedDurationSec(240)
                .evidenceQuality(JourneyLeg.EVIDENCE_QUALITY_UNKNOWN)
                .guidance(watoo.grd.nextroute.domain.journey.entity.JourneyLegGuidance.EMPTY
                        .withDirectionName("증미").withTrainKind("EXPRESS"))
                .build());
        JourneyLeg bus = legRepository.saveAndFlush(JourneyLeg.builder()
                .journeyId(session.getId()).routeRevision(1).legIndex(1)
                .type(JourneyLegType.RIDE_BUS).state(JourneyLegState.PENDING)
                .startName("a").endName("b").plannedDurationSec(300)
                .evidenceQuality(JourneyLeg.EVIDENCE_QUALITY_UNKNOWN)
                .build());

        assertThat(jdbcTemplate.queryForObject("select train_kind from journey_leg where id = ?", String.class,
                express.getId())).isEqualTo("EXPRESS");
        assertThat(legRepository.findById(express.getId()).orElseThrow().getGuidance().trainKind())
                .isEqualTo("EXPRESS");
        assertThat(legRepository.findById(express.getId()).orElseThrow().getGuidance().directionName())
                .isEqualTo("증미");
        assertThat(legRepository.findById(bus.getId()).orElseThrow().getGuidance().trainKind()).isNull();
        assertThat(jdbcTemplate.queryForObject("select character_maximum_length from information_schema.columns"
                + " where table_name = 'journey_leg' and column_name = 'train_kind'", Integer.class)).isEqualTo(16);
    }

    @Test
    @DisplayName("단계와 노선 후보가 서로를 가리키도록 저장된다")
    void leg_and_routeOption_link() {
        JourneySession session = sessionRepository.saveAndFlush(newSession(Instant.now()));

        JourneyLeg wait = legRepository.saveAndFlush(JourneyLeg.builder()
                .journeyId(session.getId())
                .routeRevision(1)
                .legIndex(0)
                .type(JourneyLegType.WAIT_BUS)
                .state(JourneyLegState.ACTIVE)
                .startStopId("STOP-1")
                .endStopId("STOP-2")
                .sourceStartStopId("odsay-stop-1")
                .sourceEndStopId("odsay-stop-2")
                .startName("정류장A")
                .plannedDurationSec(300)
                .evidenceQuality(JourneyLeg.EVIDENCE_QUALITY_UNKNOWN)
                .build());

        JourneyLegRouteOption option = routeOptionRepository.saveAndFlush(
                JourneyLegRouteOption.builder()
                        .journeyLegId(wait.getId())
                        .optionIndex(0)
                        .laneIndex(0)
                        .routeId("ROUTE-1")
                        .sourceRouteId("src-1")
                        .routeName("370")
                        .directionKey("SEQ:4>12")
                        .wayCode(1)
                        .boardingSeq(4)
                        .alightingSeq(12)
                        .build());

        wait.selectRouteOption(option.getId(), option.getRouteId());
        legRepository.saveAndFlush(wait);

        List<JourneyLeg> legs = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                        session.getId(), 1);
        assertThat(legs).hasSize(1);
        assertThat(legs.get(0).getSelectedRouteOptionId()).isEqualTo(option.getId());
        assertThat(legs.get(0).getRouteId()).isEqualTo("ROUTE-1");
        assertThat(legs.get(0).getEvidenceQuality()).isEqualTo("UNKNOWN");
        assertThat(legs.get(0).getSourceStartStopId()).isEqualTo("odsay-stop-1");
        assertThat(legs.get(0).getSourceEndStopId()).isEqualTo("odsay-stop-2");

        assertThat(routeOptionRepository.findByJourneyLegIdInAndDeletedAtIsNull(List.of(wait.getId())))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getWayCode()).isEqualTo(1);
                    assertThat(saved.getBoardingSeq()).isEqualTo(4);
                    assertThat(saved.getAlightingSeq()).isEqualTo(12);
                    assertThat(saved.getDirectionKey()).isEqualTo("SEQ:4>12");
                });
    }

    @Test
    @DisplayName("환승 연결·명령 기록·경로 개정본이 저장되고 조회된다")
    void otherTables_roundTrip() {
        JourneySession session = sessionRepository.saveAndFlush(newSession(Instant.now()));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        transferRepository.saveAndFlush(TransferConnection.builder()
                .journeyId(session.getId())
                .routeRevision(1)
                .transferIndex(0)
                .fromLegIndex(1)
                .walkLegIndex(null)
                .toLegIndex(2)
                .status(TransferStatus.UNKNOWN)
                .build());

        eventRepository.saveAndFlush(JourneyEvent.builder()
                .eventId(UUID.randomUUID())
                .journeyId(session.getId())
                .baseCommandVersion(1L)
                .routeRevision(1)
                .type("BOARDED")
                .serverReceivedAt(now)
                .payload(json("{\"legIndex\":1}"))
                .result(JourneyEventResult.APPLIED)
                .resultingCommandVersion(2L)
                .build());

        revisionRepository.saveAndFlush(JourneyRevision.builder()
                .journeyId(session.getId())
                .routeRevision(1)
                .status(JourneyRevisionStatus.ACTIVE)
                .routeSnapshot(json("{\"pathType\":1}"))
                .activatedAt(now)
                .build());

        assertThat(transferRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                        session.getId(), 1))
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.getWalkLegIndex()).isNull();
                    assertThat(t.getStatus()).isEqualTo(TransferStatus.UNKNOWN);
                });

        assertThat(revisionRepository.findByJourneyIdAndStatusAndDeletedAtIsNull(
                session.getId(), JourneyRevisionStatus.ACTIVE)).isPresent();
    }

    // ── 불변조건 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("사람당 진행 중인 여정은 하나뿐이다")
    void oneActiveJourneyPerUser() {
        sessionRepository.saveAndFlush(newSession(Instant.now()));

        assertThatThrownBy(() -> sessionRepository.saveAndFlush(newSession(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("앞 여정을 끝내면 다음 여정을 시작할 수 있다")
    void newJourneyAfterPreviousEnded() {
        JourneySession first = sessionRepository.saveAndFlush(newSession(Instant.now()));

        first.end(JourneyStatus.CANCELED, JourneyEndReason.SUPERSEDED, Instant.now());
        sessionRepository.saveAndFlush(first);

        assertThatCode(() -> sessionRepository.saveAndFlush(newSession(Instant.now())))
                .doesNotThrowAnyException();

        Optional<JourneySession> active = sessionRepository
                .findByUserIdAndStatusAndDeletedAtIsNull(userId, JourneyStatus.ACTIVE);
        assertThat(active).isPresent();
        assertThat(active.get().getId()).isNotEqualTo(first.getId());
    }

    @Test
    @DisplayName("여정당 진행 중인 경로 개정본은 하나뿐이다")
    void oneActiveRevisionPerJourney() {
        JourneySession session = sessionRepository.saveAndFlush(newSession(Instant.now()));
        Instant now = Instant.now();

        revisionRepository.saveAndFlush(JourneyRevision.builder()
                .journeyId(session.getId()).routeRevision(1)
                .status(JourneyRevisionStatus.ACTIVE)
                .routeSnapshot(json("{}")).activatedAt(now).build());

        assertThatThrownBy(() -> revisionRepository.saveAndFlush(JourneyRevision.builder()
                .journeyId(session.getId()).routeRevision(2)
                .status(JourneyRevisionStatus.ACTIVE)
                .routeSnapshot(json("{}")).activatedAt(now).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 명령 번호는 두 번 기록되지 않는다")
    void duplicateEventId_rejected() {
        JourneySession session = sessionRepository.saveAndFlush(newSession(Instant.now()));
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        eventRepository.saveAndFlush(JourneyEvent.builder()
                .eventId(eventId).journeyId(session.getId())
                .baseCommandVersion(1L).routeRevision(1).type("BOARDED")
                .serverReceivedAt(now).result(JourneyEventResult.APPLIED).build());

        assertThatThrownBy(() -> eventRepository.saveAndFlush(JourneyEvent.builder()
                .eventId(eventId).journeyId(session.getId())
                .baseCommandVersion(1L).routeRevision(1).type("BOARDED")
                .serverReceivedAt(now).result(JourneyEventResult.APPLIED).build()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(eventRepository.findByEventId(eventId)).isPresent();
    }

    @Test
    @DisplayName("소유자가 다르면 여정을 조회하지 못한다")
    void otherUsersJourney_notVisible() {
        JourneySession session = sessionRepository.saveAndFlush(newSession(Instant.now()));
        long otherUserId = jdbcTemplate.queryForObject(
                "insert into users (created_at, updated_at) values (now(), now()) returning id",
                Long.class);

        assertThat(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(
                session.getId(), userId)).isPresent();
        assertThat(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(
                session.getId(), otherUserId)).isEmpty();
    }

    @Test
    @DisplayName("트랜잭션 안에서 사용자 행을 잠그고 읽는다")
    void findByIdForUpdate_works() {
        Long locked = new TransactionTemplate(transactionManager).execute(status ->
                userRepository.findByIdForUpdate(userId).orElseThrow().getId());

        assertThat(locked).isEqualTo(userId);
    }

    @Test
    @DisplayName("트랜잭션 밖에서 잠금을 걸면 조용히 통과하지 않고 실패한다")
    void findByIdForUpdate_requiresTransaction() {
        assertThatThrownBy(() -> userRepository.findByIdForUpdate(userId))
                .as("잠금이 걸린 줄 알았는데 안 걸린 상태로 진행되는 것이 가장 위험하다")
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
    }
}
