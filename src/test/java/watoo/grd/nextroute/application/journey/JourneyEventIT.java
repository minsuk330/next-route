package watoo.grd.nextroute.application.journey;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyEventOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.JourneyQueryUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
class JourneyEventIT {

    private static final String SESSION_ID = "sess-event-it";
    private static final String PATH_KEY = "0";
    private static final Instant CLIENT_OCCURRED_AT = Instant.parse("2026-07-31T09:00:00Z");

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @Autowired JourneyQueryUseCase getActiveJourneyUseCase;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @MockBean RouteSearchSnapshotPort snapshotPort;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private JourneySnapshot journey;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, twoBusPath(), Instant.now().plusSeconds(600))));
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
    void clientBoundaryTransition_movesExactlyOneLeg() {
        JourneyEventOutcome outcome = transition(UUID.randomUUID(), 1L, 1, 0, 1);

        assertThat(outcome.result()).isEqualTo(JourneyEventResult.APPLIED);
        assertThat(outcome.journey().commandVersion()).isEqualTo(2L);
        assertThat(outcome.journey().currentLegIndex()).isEqualTo(1);
        assertThat(outcome.journey().legs().get(0).state()).isEqualTo(JourneyLegState.COMPLETED);
        assertThat(outcome.journey().legs().get(1).state()).isEqualTo(JourneyLegState.ACTIVE);
    }

    @Test
    void transitionPayload_isStoredWithoutCoordinates() {
        UUID eventId = UUID.randomUUID();
        transition(eventId, 1L, 1, 0, 1);

        String payload = jdbcTemplate.queryForObject(
                "select payload::text from journey_event where event_id = ?", String.class, eventId);
        assertThat(payload).contains("fromLegIndex", "toLegIndex", "0", "1")
                .doesNotContain("latitude", "longitude");
    }

    @Test
    void fullRouteProgress_needsNoBoardingOrAlightingConfirmation() {
        JourneySnapshot current = journey;
        for (int from = 0; from + 1 < current.legs().size(); from++) {
            current = transition(UUID.randomUUID(), current.commandVersion(),
                    current.routeRevision(), from, from + 1).journey();
        }

        assertThat(current.currentLegIndex()).isEqualTo(current.legs().size() - 1);
        int lastLegIndex = current.legs().size() - 1;
        assertThat(current.legs()).allSatisfy(leg -> {
            if (leg.legIndex() < lastLegIndex) {
                assertThat(leg.state()).isEqualTo(JourneyLegState.COMPLETED);
            }
        });
    }

    @Test
    void everyTransitKeepsPrimaryRouteAndAlternatives() {
        assertThat(journey.legs().get(0).selectedRouteOptionId()).isNotNull();
        assertThat(journey.legs().get(1).selectedRouteOptionId())
                .isEqualTo(journey.legs().get(0).selectedRouteOptionId());
        assertThat(journey.legs().get(3).routeOptions()).hasSize(2);
        assertThat(journey.legs().get(3).selectedRouteOptionId())
                .isEqualTo(journey.legs().get(3).routeOptions().get(0).optionId());
        assertThat(journey.legs().get(4).selectedRouteOptionId())
                .isEqualTo(journey.legs().get(3).selectedRouteOptionId());
    }

    @Test
    void enteringAndLeavingSubway_setsAndClearsOfflineProtection() {
        Long subwayUser = newUser();
        given(snapshotPort.find("subway-session", PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(null, subwayPath(),
                        Instant.now().plusSeconds(600))));
        JourneySnapshot subwayJourney = startJourneyUseCase.start(subwayUser,
                new StartJourneyCommand("subway-session", PATH_KEY, false));

        JourneySnapshot riding = applyJourneyEventUseCase.apply(subwayUser, subwayJourney.journeyId(),
                command(UUID.randomUUID(), JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        1L, 1, 0, 1)).journey();
        assertThat(riding.subwayOfflineProtectionUntil()).isNotNull();
        assertThat(riding.subwayOfflineProtectionUntil()).isAfter(riding.guidanceLeaseUntil());

        JourneySnapshot walking = applyJourneyEventUseCase.apply(subwayUser, subwayJourney.journeyId(),
                command(UUID.randomUUID(), JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        2L, 1, 1, 2)).journey();
        assertThat(walking.subwayOfflineProtectionUntil()).isNull();
    }

    @Test
    void duplicateEvent_doesNotAdvanceTwice() {
        UUID eventId = UUID.randomUUID();
        JourneyEventOutcome first = transition(eventId, 1L, 1, 0, 1);
        JourneyEventOutcome second = transition(eventId, 1L, 1, 0, 1);

        assertThat(first.result()).isEqualTo(JourneyEventResult.APPLIED);
        assertThat(second.result()).isEqualTo(JourneyEventResult.DUPLICATE);
        assertThat(second.journey().commandVersion()).isEqualTo(2L);
        assertThat(eventCount()).isEqualTo(1);
    }

    @Test
    void replayOfConflict_keepsTheOriginalConflict() {
        UUID eventId = UUID.randomUUID();
        JourneyEventOutcome first = transition(eventId, 99L, 1, 0, 1);
        JourneyEventOutcome replay = transition(eventId, 99L, 1, 0, 1);

        assertThat(first.result()).isEqualTo(JourneyEventResult.CONFLICT);
        assertThat(replay.result()).isEqualTo(JourneyEventResult.CONFLICT);
        assertThat(replay.rejectionReason()).isEqualTo(first.rejectionReason());
    }

    @Test
    void eventIdCannotBeReusedForAnotherJourney() {
        UUID eventId = UUID.randomUUID();
        transition(eventId, 1L, 1, 0, 1);

        Long other = newUser();
        JourneySnapshot otherJourney = startJourneyUseCase.start(other,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
        JourneyEventOutcome reused = applyJourneyEventUseCase.apply(other, otherJourney.journeyId(),
                command(eventId, JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        1L, 1, 0, 1));

        assertThat(reused.result()).isEqualTo(JourneyEventResult.CONFLICT);
        assertThat(reused.rejectionReason()).isEqualTo("EVENT_ID_REUSED");
        assertThat(reused.journey().currentLegIndex()).isZero();
    }

    @Test
    void eventIdCannotBeReusedForDifferentPayloadInSameJourney() {
        UUID eventId = UUID.randomUUID();
        transition(eventId, 1L, 1, 0, 1);

        JourneyEventOutcome reused = transition(eventId, 1L, 1, 0, 2);

        assertThat(reused.result()).isEqualTo(JourneyEventResult.CONFLICT);
        assertThat(reused.rejectionReason()).isEqualTo("EVENT_ID_REUSED");
        assertThat(reused.journey().currentLegIndex()).isEqualTo(1);
        assertThat(eventCount()).isEqualTo(1);
    }

    @Test
    void staleVersionAndRevision_areConflicts() {
        transition(UUID.randomUUID(), 1L, 1, 0, 1);

        JourneyEventOutcome staleVersion = transition(UUID.randomUUID(), 1L, 1, 1, 2);
        JourneyEventOutcome staleRevision = transition(UUID.randomUUID(), 2L, 99, 1, 2);

        assertThat(staleVersion.result()).isEqualTo(JourneyEventResult.CONFLICT);
        assertThat(staleRevision.result()).isEqualTo(JourneyEventResult.CONFLICT);
        assertThat(getActiveJourneyUseCase.getActive(userId).orElseThrow().currentLegIndex())
                .isEqualTo(1);
    }

    @Test
    void unexpectedLegAndSkipping_areConflicts() {
        assertThat(transition(UUID.randomUUID(), 1L, 1, 1, 2).result())
                .isEqualTo(JourneyEventResult.CONFLICT);
        assertThat(transition(UUID.randomUUID(), 1L, 1, 0, 2).result())
                .isEqualTo(JourneyEventResult.CONFLICT);
    }

    @Test
    void complete_marksCurrentLegAndEndsJourney() {
        JourneyEventOutcome outcome = send(JourneyEventType.JOURNEY_COMPLETED, 1L, 1);

        assertThat(outcome.journey().status()).isEqualTo(JourneyStatus.COMPLETED);
        assertThat(outcome.journey().currentLeg().state()).isEqualTo(JourneyLegState.COMPLETED);
        assertThat(getActiveJourneyUseCase.getActive(userId)).isEmpty();
    }

    @Test
    void cancel_endsJourneyAndLaterCommandConflicts() {
        JourneySnapshot canceled = send(JourneyEventType.JOURNEY_CANCELED, 1L, 1).journey();

        assertThat(canceled.status()).isEqualTo(JourneyStatus.CANCELED);
        JourneyEventOutcome afterEnd = transition(UUID.randomUUID(), 2L, 1, 0, 1);
        assertThat(afterEnd.result()).isEqualTo(JourneyEventResult.CONFLICT);
    }

    @Test
    void stateSurvivesPersistenceContextClear() {
        transition(UUID.randomUUID(), 1L, 1, 0, 1);
        entityManager.clear();

        JourneySnapshot reloaded = getActiveJourneyUseCase.getActive(userId).orElseThrow();
        assertThat(reloaded.commandVersion()).isEqualTo(2L);
        assertThat(reloaded.currentLegIndex()).isEqualTo(1);
    }

    @Test
    void otherUserCannotReadOrCommandJourney() {
        Long other = newUser();

        assertThat(getActiveJourneyUseCase.get(other, journey.journeyId())).isEmpty();
        assertThatThrownBy(() -> applyJourneyEventUseCase.apply(other, journey.journeyId(),
                command(UUID.randomUUID(), JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        1L, 1, 0, 1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(JourneyErrorCode.JOURNEY_NOT_FOUND));
    }

    private JourneyEventOutcome transition(UUID eventId, long version, int revision,
                                           int from, int to) {
        return applyJourneyEventUseCase.apply(userId, journey.journeyId(),
                command(eventId, JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        version, revision, from, to));
    }

    private JourneyEventOutcome send(JourneyEventType type, long version, int revision) {
        return applyJourneyEventUseCase.apply(userId, journey.journeyId(),
                command(UUID.randomUUID(), type, version, revision, null, null));
    }

    private JourneyCommand command(UUID eventId, JourneyEventType type, long version, int revision,
                                   Integer from, Integer to) {
        return new JourneyCommand(eventId, type, version, revision, from, to, CLIENT_OCCURRED_AT);
    }

    private int eventCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from journey_event where journey_id = ?",
                Integer.class, journey.journeyId());
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

    private PathResult twoBusPath() {
        return new PathResult(1,
                new PathInfo(40, 2500, 600, 1, "정류장A", "정류장Z", null),
                List.of(
                        SubPathFixture.bus(20).names("정류장A", "정류장F")
                                .busStops("ST-A", "ST-F")
                                .lanes(SubPathFixture.busLane("370", "BL-370")).build(),
                        SubPathFixture.walk(3).names("정류장F", "정류장G").distance(150.0).build(),
                        SubPathFixture.bus(15).names("정류장G", "정류장Z")
                                .busStops("ST-G", "ST-Z")
                                .lanes(SubPathFixture.busLane("721", "BL-721"),
                                        SubPathFixture.busLane("140", "BL-140")).build()),
                List.of());
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
