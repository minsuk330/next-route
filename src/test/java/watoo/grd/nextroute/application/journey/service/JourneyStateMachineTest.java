package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyLegView;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionView;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.JourneyTransition;
import watoo.grd.nextroute.application.journey.dto.LegStateChange;
import watoo.grd.nextroute.domain.journey.entity.JourneyEndReason;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상태 전이 규칙을 고정한다.
 *
 * <p>여기서 확인하는 것은 "무엇이 허용되고 무엇이 거절되는가"다. 규칙이 틀리면 사용자가 버튼을
 * 눌렀는데 아무 일도 일어나지 않거나, 지나온 단계로 되돌아간다. 둘 다 화면에서 바로 드러나지만
 * 원인을 찾기는 어렵다.
 */
class JourneyStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-07-31T09:00:00Z");

    private final JourneyStateMachine stateMachine = new JourneyStateMachine();

    // ── 준비 도구 ────────────────────────────────────────────────────────────

    /** 걷기 → 버스 → 환승도보 → 지하철 → 걷기. 실제 compiler 가 만드는 모양이다. */
    private List<JourneyLegView> fullRoute() {
        return new ArrayList<>(List.of(
                leg(0, JourneyLegType.WALK, JourneyLegState.ACTIVE),
                leg(1, JourneyLegType.WAIT_BUS, JourneyLegState.PENDING, option(100L), option(101L)),
                leg(2, JourneyLegType.RIDE_BUS, JourneyLegState.PENDING),
                leg(3, JourneyLegType.TRANSFER_WALK, JourneyLegState.PENDING),
                leg(4, JourneyLegType.WAIT_SUBWAY, JourneyLegState.PENDING, option(200L)),
                leg(5, JourneyLegType.RIDE_SUBWAY, JourneyLegState.PENDING),
                leg(6, JourneyLegType.WALK, JourneyLegState.PENDING)));
    }

    private JourneyLegView leg(int index, JourneyLegType type, JourneyLegState state,
                               JourneyRouteOptionView... options) {
        return new JourneyLegView((long) index + 10, index, type, state,
                "ST-" + index, "ST-" + (index + 1), "출발" + index, "도착" + index,
                600, null, null, Arrays.asList(options));
    }

    private JourneyRouteOptionView option(Long id) {
        return new JourneyRouteOptionView(id, 0, 0, null, "BL-" + id, String.valueOf(id));
    }

    private JourneySnapshot journey(int currentLegIndex, List<JourneyLegView> legs) {
        return journey(JourneyStatus.ACTIVE, 1L, 1, currentLegIndex, legs);
    }

    private JourneySnapshot journey(JourneyStatus status, long commandVersion, int routeRevision,
                                    int currentLegIndex, List<JourneyLegView> legs) {
        return new JourneySnapshot(1L, status, commandVersion, 0L, routeRevision,
                currentLegIndex, NOW.plusSeconds(180), NOW, legs);
    }

    private List<JourneyLegView> withState(List<JourneyLegView> legs, int index,
                                           JourneyLegState state) {
        List<JourneyLegView> copy = new ArrayList<>(legs);
        JourneyLegView old = copy.get(index);
        copy.set(index, new JourneyLegView(old.legId(), old.legIndex(), old.type(), state,
                old.startStopId(), old.endStopId(), old.startName(), old.endName(),
                old.plannedDurationSec(), old.evidenceQuality(), old.selectedRouteOptionId(),
                old.routeOptions()));
        return copy;
    }

    private JourneyCommand command(JourneyEventType type) {
        return new JourneyCommand(UUID.randomUUID(), type, 1L, 1, null);
    }

    private JourneyCommand command(JourneyEventType type, Long routeOptionId) {
        return new JourneyCommand(UUID.randomUUID(), type, 1L, 1, routeOptionId);
    }

    // ── 문지기 ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("명령을 받기 전에")
    class Guards {

        @Test
        @DisplayName("끝난 여정은 더 이상 명령을 받지 않는다")
        void endedJourney_rejects() {
            JourneySnapshot ended = journey(JourneyStatus.COMPLETED, 1L, 1, 6, fullRoute());

            JourneyTransition t = stateMachine.decide(
                    ended, command(JourneyEventType.USER_CONFIRMED_BOARDING));

            assertThat(t.result()).isEqualTo(JourneyEventResult.REJECTED);
            assertThat(t.rejectionReason()).isEqualTo(JourneyStateMachine.JOURNEY_NOT_ACTIVE);
        }

        @Test
        @DisplayName("낡은 명령 번호는 거절이 아니라 충돌이다")
        void staleCommandVersion_conflicts() {
            JourneySnapshot moved = journey(JourneyStatus.ACTIVE, 5L, 1, 1, fullRoute());

            JourneyTransition t = stateMachine.decide(
                    moved, new JourneyCommand(UUID.randomUUID(),
                            JourneyEventType.USER_CONFIRMED_BOARDING, 3L, 1, null));

            assertThat(t.result())
                    .as("사용자가 잘못한 것이 아니라 그 사이 상태가 움직인 것이다")
                    .isEqualTo(JourneyEventResult.CONFLICT);
            assertThat(t.rejectionReason()).isEqualTo(JourneyStateMachine.STALE_COMMAND_VERSION);
            assertThat(t.commandVersionIncrement()).isFalse();
        }

        @Test
        @DisplayName("이전 경로를 가리키는 명령은 적용하지 않는다")
        void staleRouteRevision_conflicts() {
            JourneySnapshot rerouted = journey(JourneyStatus.ACTIVE, 1L, 2, 1, fullRoute());

            JourneyTransition t = stateMachine.decide(
                    rerouted, new JourneyCommand(UUID.randomUUID(),
                            JourneyEventType.USER_CONFIRMED_BOARDING, 1L, 1, null));

            assertThat(t.result()).isEqualTo(JourneyEventResult.CONFLICT);
            assertThat(t.rejectionReason()).isEqualTo(JourneyStateMachine.STALE_ROUTE_REVISION);
        }

        @Test
        @DisplayName("경로 개정본을 명령 번호보다 먼저 본다")
        void routeRevisionCheckedFirst() {
            JourneySnapshot rerouted = journey(JourneyStatus.ACTIVE, 9L, 2, 1, fullRoute());

            JourneyTransition t = stateMachine.decide(
                    rerouted, new JourneyCommand(UUID.randomUUID(),
                            JourneyEventType.USER_CONFIRMED_BOARDING, 1L, 1, null));

            assertThat(t.rejectionReason())
                    .as("둘 다 어긋났으면 더 큰 변화인 경로 교체를 알려 주는 편이 낫다")
                    .isEqualTo(JourneyStateMachine.STALE_ROUTE_REVISION);
        }
    }

    // ── B1 탑승 ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("탔어요를 누르면")
    class Boarding {

        @Test
        @DisplayName("기다리기는 완료되고 타고가기가 진행 중이 된다")
        void waitCompleted_rideActive() {
            JourneySnapshot j = journey(1, withState(fullRoute(), 1, JourneyLegState.ACTIVE));

            JourneyTransition t = stateMachine.decide(
                    j, command(JourneyEventType.USER_CONFIRMED_BOARDING));

            assertThat(t.isApplied()).isTrue();
            assertThat(t.legStateChanges()).containsExactly(
                    new LegStateChange(1, JourneyLegState.COMPLETED),
                    new LegStateChange(2, JourneyLegState.ACTIVE));
            assertThat(t.newCurrentLegIndex()).isEqualTo(2);
            assertThat(t.commandVersionIncrement()).isTrue();
        }

        @Test
        @DisplayName("사이에 있는 걷기는 함께 완료된다")
        void walkBefore_autoCompleted() {
            JourneySnapshot j = journey(0, fullRoute());

            JourneyTransition t = stateMachine.decide(
                    j, command(JourneyEventType.USER_CONFIRMED_BOARDING));

            assertThat(t.legStateChanges())
                    .as("걷기를 끝냈다고 따로 눌러 주지 않는다")
                    .containsExactly(
                            new LegStateChange(0, JourneyLegState.COMPLETED),
                            new LegStateChange(1, JourneyLegState.COMPLETED),
                            new LegStateChange(2, JourneyLegState.ACTIVE));
            assertThat(t.newCurrentLegIndex()).isEqualTo(2);
        }

        @Test
        @DisplayName("환승 뒤 두 번째 탑승도 같은 규칙이다")
        void secondBoarding() {
            List<JourneyLegView> legs = withState(fullRoute(), 3, JourneyLegState.ACTIVE);
            JourneySnapshot j = journey(3, legs);

            JourneyTransition t = stateMachine.decide(
                    j, command(JourneyEventType.USER_CONFIRMED_BOARDING));

            assertThat(t.legStateChanges()).containsExactly(
                    new LegStateChange(3, JourneyLegState.COMPLETED),
                    new LegStateChange(4, JourneyLegState.COMPLETED),
                    new LegStateChange(5, JourneyLegState.ACTIVE));
            assertThat(t.newCurrentLegIndex()).isEqualTo(5);
        }

        @Test
        @DisplayName("탈 것이 남지 않았으면 거절한다")
        void noBoardableLeg_rejected() {
            JourneySnapshot j = journey(6, withState(fullRoute(), 6, JourneyLegState.ACTIVE));

            JourneyTransition t = stateMachine.decide(
                    j, command(JourneyEventType.USER_CONFIRMED_BOARDING));

            assertThat(t.result()).isEqualTo(JourneyEventResult.REJECTED);
            assertThat(t.rejectionReason()).isEqualTo(JourneyStateMachine.NO_BOARDABLE_LEG);
        }

        @Test
        @DisplayName("노선을 아직 고르지 않았어도 탑승을 막지 않는다")
        void boardingAllowed_withoutVehicleSelection() {
            JourneySnapshot j = journey(1, withState(fullRoute(), 1, JourneyLegState.ACTIVE));

            JourneyTransition t = stateMachine.decide(
                    j, command(JourneyEventType.USER_CONFIRMED_BOARDING));

            assertThat(t.isApplied())
                    .as("막으면 실시간 정보가 없는 구간에서 아예 진행할 수 없다")
                    .isTrue();
        }
    }

    // ── B2 하차 ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("내렸어요를 누르면")
    class Alighting {

        @Test
        @DisplayName("타고가기가 완료되고 다음 단계가 진행 중이 된다")
        void rideCompleted_nextActive() {
            JourneySnapshot j = journey(2, withState(fullRoute(), 2, JourneyLegState.ACTIVE));

            JourneyTransition t = stateMachine.decide(
                    j, command(JourneyEventType.USER_CONFIRMED_ALIGHTING));

            assertThat(t.legStateChanges()).containsExactly(
                    new LegStateChange(2, JourneyLegState.COMPLETED),
                    new LegStateChange(3, JourneyLegState.ACTIVE));
            assertThat(t.newCurrentLegIndex()).isEqualTo(3);
            assertThat(t.newStatus()).isNull();
        }

        @Test
        @DisplayName("타고 있지 않으면 거절한다")
        void notRiding_rejected() {
            JourneySnapshot j = journey(1, withState(fullRoute(), 1, JourneyLegState.ACTIVE));

            JourneyTransition t = stateMachine.decide(
                    j, command(JourneyEventType.USER_CONFIRMED_ALIGHTING));

            assertThat(t.result())
                    .as("조용히 무시하면 눌렀는데 아무 일도 없는 것처럼 보인다")
                    .isEqualTo(JourneyEventResult.REJECTED);
            assertThat(t.rejectionReason()).isEqualTo(JourneyStateMachine.NOT_RIDING);
        }

        @Test
        @DisplayName("마지막 단계에서 내리면 여정이 끝난다")
        void lastLeg_completesJourney() {
            List<JourneyLegView> legs = new ArrayList<>(List.of(
                    leg(0, JourneyLegType.WAIT_BUS, JourneyLegState.COMPLETED),
                    leg(1, JourneyLegType.RIDE_BUS, JourneyLegState.ACTIVE)));
            JourneySnapshot j = journey(1, legs);

            JourneyTransition t = stateMachine.decide(
                    j, command(JourneyEventType.USER_CONFIRMED_ALIGHTING));

            assertThat(t.newStatus()).isEqualTo(JourneyStatus.COMPLETED);
            assertThat(t.endReason()).isEqualTo(JourneyEndReason.COMPLETED);
            assertThat(t.legStateChanges())
                    .containsExactly(new LegStateChange(1, JourneyLegState.COMPLETED));
        }

        @Test
        @DisplayName("마지막 걷기가 남아 있으면 여정은 계속된다")
        void walkRemaining_journeyContinues() {
            JourneySnapshot j = journey(5, withState(fullRoute(), 5, JourneyLegState.ACTIVE));

            JourneyTransition t = stateMachine.decide(
                    j, command(JourneyEventType.USER_CONFIRMED_ALIGHTING));

            assertThat(t.newStatus()).isNull();
            assertThat(t.newCurrentLegIndex()).isEqualTo(6);
        }
    }

    // ── B3 차량 선택 ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("탈 노선을 고르면")
    class VehicleSelection {

        @Test
        @DisplayName("기다리기와 타고가기 두 단계가 같은 노선을 가리킨다")
        void selectsBothLegs() {
            JourneySnapshot j = journey(1, withState(fullRoute(), 1, JourneyLegState.ACTIVE));

            JourneyTransition t = stateMachine.decide(
                    j, command(JourneyEventType.USER_SELECTED_VEHICLE, 101L));

            assertThat(t.isApplied()).isTrue();
            assertThat(t.selectedRouteOptionId()).isEqualTo(101L);
            assertThat(t.routeOptionTargetLegs())
                    .as("한쪽만 바꾸면 기다릴 때와 탈 때가 어긋난다")
                    .containsExactly(1, 2);
            assertThat(t.legStateChanges()).isEmpty();
        }

        @Test
        @DisplayName("이미 타고 있어도 바꿀 수 있다")
        void changeWhileRiding() {
            JourneySnapshot j = journey(2, withState(fullRoute(), 2, JourneyLegState.ACTIVE));

            JourneyTransition t = stateMachine.decide(
                    j, command(JourneyEventType.USER_CHANGED_VEHICLE, 100L));

            assertThat(t.isApplied())
                    .as("잘못 탄 것을 알아차리는 시점은 대개 탄 뒤다")
                    .isTrue();
            assertThat(t.routeOptionTargetLegs()).containsExactly(1, 2);
        }

        @Test
        @DisplayName("후보에 없는 노선은 거절한다")
        void unknownOption_rejected() {
            JourneySnapshot j = journey(1, withState(fullRoute(), 1, JourneyLegState.ACTIVE));

            JourneyTransition t = stateMachine.decide(
                    j, command(JourneyEventType.USER_SELECTED_VEHICLE, 999L));

            assertThat(t.rejectionReason()).isEqualTo(JourneyStateMachine.UNKNOWN_ROUTE_OPTION);
        }

        @Test
        @DisplayName("노선을 안 보내면 거절한다")
        void missingOption_rejected() {
            JourneySnapshot j = journey(1, withState(fullRoute(), 1, JourneyLegState.ACTIVE));

            JourneyTransition t = stateMachine.decide(
                    j, command(JourneyEventType.USER_SELECTED_VEHICLE, null));

            assertThat(t.rejectionReason()).isEqualTo(JourneyStateMachine.ROUTE_OPTION_REQUIRED);
        }

        @Test
        @DisplayName("걷는 중에는 고를 노선이 없다")
        void duringWalk_rejected() {
            JourneySnapshot j = journey(0, fullRoute());

            JourneyTransition t = stateMachine.decide(
                    j, command(JourneyEventType.USER_SELECTED_VEHICLE, 100L));

            assertThat(t.rejectionReason()).isEqualTo(JourneyStateMachine.NO_ROUTE_OPTION_LEG);
        }
    }

    // ── 종료와 무변화 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("그 밖의 명령은")
    class Others {

        @Test
        @DisplayName("취소하면 이유와 함께 끝난다")
        void cancel() {
            JourneyTransition t = stateMachine.decide(
                    journey(2, fullRoute()), command(JourneyEventType.JOURNEY_CANCELED));

            assertThat(t.newStatus()).isEqualTo(JourneyStatus.CANCELED);
            assertThat(t.endReason()).isEqualTo(JourneyEndReason.USER_CANCELED);
            assertThat(t.legStateChanges())
                    .as("어디까지 갔다가 접었는지가 남아야 한다")
                    .isEmpty();
        }

        @Test
        @DisplayName("lease 가 끝나면 서버가 접는다")
        void leaseExpired() {
            JourneyTransition t = stateMachine.decide(
                    journey(2, fullRoute()), command(JourneyEventType.LEASE_EXPIRED));

            assertThat(t.newStatus()).isEqualTo(JourneyStatus.EXPIRED);
            assertThat(t.endReason()).isEqualTo(JourneyEndReason.CLIENT_LEASE_EXPIRED);
        }

        @Test
        @DisplayName("앱 복귀는 적용이되 명령 번호를 올리지 않는다")
        void appResumed_noVersionBump() {
            JourneyTransition t = stateMachine.decide(
                    journey(2, fullRoute()), command(JourneyEventType.APP_RESUMED));

            assertThat(t.isApplied()).isTrue();
            assertThat(t.commandVersionIncrement())
                    .as("올리면 앱을 켰다 껐다 하는 것만으로 손에 든 번호가 낡는다")
                    .isFalse();
            assertThat(t.legStateChanges()).isEmpty();
        }

        @Test
        @DisplayName("정류장 근접 신호도 상태를 바꾸지 않는다")
        void stopProximity_noChange() {
            JourneyTransition t = stateMachine.decide(
                    journey(2, fullRoute()), command(JourneyEventType.STOP_PROXIMITY_DETECTED));

            assertThat(t.isApplied()).isTrue();
            assertThat(t.commandVersionIncrement()).isFalse();
        }

        @Test
        @DisplayName("재탐색 수락은 아직 지원하지 않는다")
        void rerouteAccepted_notYet() {
            JourneyTransition t = stateMachine.decide(
                    journey(2, fullRoute()), command(JourneyEventType.REROUTE_ACCEPTED));

            assertThat(t.result()).isEqualTo(JourneyEventResult.REJECTED);
            assertThat(t.rejectionReason()).isEqualTo(JourneyStateMachine.NOT_SUPPORTED_YET);
        }
    }

    // ── 불변식 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("단계는 뒤로 가지 않는다")
    void neverMovesBackward() {
        JourneySnapshot j = journey(3, withState(fullRoute(), 3, JourneyLegState.ACTIVE));

        JourneyTransition t = stateMachine.decide(
                j, command(JourneyEventType.USER_CONFIRMED_BOARDING));

        assertThat(t.newCurrentLegIndex()).isGreaterThan(j.currentLegIndex());
        assertThat(t.legStateChanges())
                .allSatisfy(c -> assertThat(c.legIndex()).isGreaterThanOrEqualTo(j.currentLegIndex()));
    }

    @Test
    @DisplayName("진행 중인 단계는 언제나 하나다")
    void exactlyOneActiveLeg() {
        JourneySnapshot j = journey(0, fullRoute());

        JourneyTransition t = stateMachine.decide(
                j, command(JourneyEventType.USER_CONFIRMED_BOARDING));

        assertThat(t.legStateChanges())
                .filteredOn(c -> c.state() == JourneyLegState.ACTIVE)
                .hasSize(1);
    }
}
