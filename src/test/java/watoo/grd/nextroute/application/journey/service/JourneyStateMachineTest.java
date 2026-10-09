package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyLegView;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.JourneyTransition;
import watoo.grd.nextroute.domain.journey.entity.JourneyEndReason;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-08-03T09:00:00Z");
    private final JourneyStateMachine stateMachine = new JourneyStateMachine();

    @Test
    void confirmedBoundary_completesCurrentAndActivatesNext() {
        JourneyTransition transition = stateMachine.decide(
                journey(JourneyStatus.ACTIVE, 1L, 1, 0, route()), transition(1L, 1, 0, 1));

        assertThat(transition.result()).isEqualTo(JourneyEventResult.APPLIED);
        assertThat(transition.newCurrentLegIndex()).isEqualTo(1);
        assertThat(transition.legStateChanges()).extracting(change -> change.state())
                .containsExactly(JourneyLegState.COMPLETED, JourneyLegState.ACTIVE);
        assertThat(transition.commandVersionIncrement()).isTrue();
    }

    @Test
    void eachBoundary_usesTheSameTransitionContract() {
        List<JourneyLegView> legs = route();
        for (int from = 0; from + 1 < legs.size(); from++) {
            List<JourneyLegView> atBoundary = withCurrent(legs, from);
            JourneyTransition transition = stateMachine.decide(
                    journey(JourneyStatus.ACTIVE, 1L, 1, from, atBoundary),
                    transition(1L, 1, from, from + 1));
            assertThat(transition.result()).isEqualTo(JourneyEventResult.APPLIED);
            assertThat(transition.newCurrentLegIndex()).isEqualTo(from + 1);
        }
    }

    @Test
    void expectedCurrentLegMismatch_isConflict() {
        JourneyTransition transition = stateMachine.decide(
                journey(JourneyStatus.ACTIVE, 1L, 1, 1, withCurrent(route(), 1)),
                transition(1L, 1, 0, 1));

        assertThat(transition.result()).isEqualTo(JourneyEventResult.CONFLICT);
        assertThat(transition.rejectionReason()).isEqualTo(JourneyStateMachine.EXPECTED_LEG_MISMATCH);
    }

    @Test
    void skipOrReverse_isConflict() {
        JourneySnapshot journey = journey(JourneyStatus.ACTIVE, 1L, 1, 1, withCurrent(route(), 1));

        assertThat(stateMachine.decide(journey, transition(1L, 1, 1, 3)).rejectionReason())
                .isEqualTo(JourneyStateMachine.INVALID_TRANSITION);
        assertThat(stateMachine.decide(journey, transition(1L, 1, 1, 0)).rejectionReason())
                .isEqualTo(JourneyStateMachine.INVALID_TRANSITION);
    }

    @Test
    void malformedTransition_isRejected() {
        JourneyCommand malformed = new JourneyCommand(UUID.randomUUID(),
                JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED, 1L, 1,
                null, null, NOW);

        JourneyTransition transition = stateMachine.decide(
                journey(JourneyStatus.ACTIVE, 1L, 1, 0, route()), malformed);

        assertThat(transition.result()).isEqualTo(JourneyEventResult.REJECTED);
        assertThat(transition.rejectionReason())
                .isEqualTo(JourneyStateMachine.INVALID_LEG_TRANSITION_PAYLOAD);
    }

    @Test
    void staleCommandVersion_isConflict() {
        JourneyTransition transition = stateMachine.decide(
                journey(JourneyStatus.ACTIVE, 2L, 1, 0, route()), transition(1L, 1, 0, 1));

        assertThat(transition.result()).isEqualTo(JourneyEventResult.CONFLICT);
        assertThat(transition.rejectionReason()).isEqualTo(JourneyStateMachine.STALE_COMMAND_VERSION);
    }

    @Test
    void staleRouteRevision_isConflict() {
        JourneyTransition transition = stateMachine.decide(
                journey(JourneyStatus.ACTIVE, 1L, 2, 0, route()), transition(1L, 1, 0, 1));

        assertThat(transition.result()).isEqualTo(JourneyEventResult.CONFLICT);
        assertThat(transition.rejectionReason()).isEqualTo(JourneyStateMachine.STALE_ROUTE_REVISION);
    }

    @Test
    void endedJourney_isConflict() {
        JourneyTransition transition = stateMachine.decide(
                journey(JourneyStatus.CANCELED, 2L, 1, 0, route()), transition(2L, 1, 0, 1));

        assertThat(transition.result()).isEqualTo(JourneyEventResult.CONFLICT);
        assertThat(transition.rejectionReason()).isEqualTo(JourneyStateMachine.JOURNEY_NOT_ACTIVE);
    }

    @Test
    void complete_finishesCurrentLegAndJourney() {
        JourneyTransition transition = stateMachine.decide(
                journey(JourneyStatus.ACTIVE, 1L, 1, 0, route()),
                command(JourneyEventType.JOURNEY_COMPLETED));

        assertThat(transition.newStatus()).isEqualTo(JourneyStatus.COMPLETED);
        assertThat(transition.endReason()).isEqualTo(JourneyEndReason.COMPLETED);
        assertThat(transition.legStateChanges()).singleElement()
                .satisfies(change -> assertThat(change.state()).isEqualTo(JourneyLegState.COMPLETED));
    }

    @Test
    void cancelAndLeaseExpiry_keepDifferentEndReasons() {
        JourneySnapshot journey = journey(JourneyStatus.ACTIVE, 1L, 1, 0, route());

        JourneyTransition canceled = stateMachine.decide(journey,
                command(JourneyEventType.JOURNEY_CANCELED));
        JourneyTransition expired = stateMachine.decide(journey,
                command(JourneyEventType.LEASE_EXPIRED));

        assertThat(canceled.endReason()).isEqualTo(JourneyEndReason.USER_CANCELED);
        assertThat(expired.endReason()).isEqualTo(JourneyEndReason.CLIENT_LEASE_EXPIRED);
    }

    @Test
    void rerouteAcceptance_staysPhase6() {
        JourneyTransition transition = stateMachine.decide(
                journey(JourneyStatus.ACTIVE, 1L, 1, 0, route()),
                command(JourneyEventType.REROUTE_ACCEPTED));

        assertThat(transition.result()).isEqualTo(JourneyEventResult.REJECTED);
        assertThat(transition.rejectionReason()).isEqualTo(JourneyStateMachine.NOT_SUPPORTED_YET);
    }

    private JourneyCommand transition(long version, int revision, int from, int to) {
        return new JourneyCommand(UUID.randomUUID(),
                JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                version, revision, from, to, NOW);
    }

    private JourneyCommand command(JourneyEventType type) {
        return new JourneyCommand(UUID.randomUUID(), type, 1L, 1, null, null, NOW);
    }

    private JourneySnapshot journey(JourneyStatus status, long version, int revision,
                                    int currentLeg, List<JourneyLegView> legs) {
        return JourneySnapshot.of(1L, status, JourneyKind.NAVIGATION, version, 0L, revision, currentLeg,
                NOW.plusSeconds(180), null, NOW, legs);
    }

    private List<JourneyLegView> route() {
        return List.of(
                leg(0, JourneyLegType.WAIT_BUS, JourneyLegState.ACTIVE),
                leg(1, JourneyLegType.RIDE_BUS, JourneyLegState.PENDING),
                leg(2, JourneyLegType.TRANSFER_WALK, JourneyLegState.PENDING),
                leg(3, JourneyLegType.WAIT_SUBWAY, JourneyLegState.PENDING),
                leg(4, JourneyLegType.RIDE_SUBWAY, JourneyLegState.PENDING),
                leg(5, JourneyLegType.WALK, JourneyLegState.PENDING));
    }

    private JourneyLegView leg(int index, JourneyLegType type, JourneyLegState state) {
        return new JourneyLegView((long) index + 10, index, type, state,
                "S" + index, "E" + index, "start", "end", null,
                60, null, null, List.of());
    }

    private List<JourneyLegView> withCurrent(List<JourneyLegView> source, int current) {
        List<JourneyLegView> copy = new ArrayList<>(source.size());
        for (JourneyLegView leg : source) {
            JourneyLegState state = leg.legIndex() < current
                    ? JourneyLegState.COMPLETED
                    : leg.legIndex() == current ? JourneyLegState.ACTIVE : JourneyLegState.PENDING;
            copy.add(new JourneyLegView(leg.legId(), leg.legIndex(), leg.type(), state,
                    leg.startStopId(), leg.endStopId(), leg.startName(), leg.endName(), leg.routeId(),
                    leg.plannedDurationSec(), leg.evidenceQuality(), leg.selectedRouteOptionId(),
                    leg.routeOptions()));
        }
        return copy;
    }
}
