package watoo.grd.nextroute.application.journey.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyLegView;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionView;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.JourneyTransition;
import watoo.grd.nextroute.application.journey.dto.LegStateChange;
import watoo.grd.nextroute.domain.journey.entity.JourneyEndReason;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * 명령 하나를 받아 여정이 어떻게 바뀌어야 하는지 결정한다.
 *
 * <p>저장소를 보지 않고 아무것도 저장하지 않는다. 입력은 지금 상태와 명령, 출력은 지시서다.
 * 상태 전이 규칙은 사람이 읽고 따져야 하는 종류의 코드라, DB 없이 확인할 수 있어야 한다.
 *
 * <h2>지키는 것</h2>
 * <ul>
 *   <li>진행 중인 단계는 언제나 하나다</li>
 *   <li>단계는 뒤로 가지 않는다</li>
 *   <li>끝난 여정은 더 이상 명령을 받지 않는다</li>
 *   <li>앱이 낡은 상태를 보고 누른 명령은 적용하지 않는다</li>
 * </ul>
 */
@Component
public class JourneyStateMachine {

    // 거절·충돌 이유. 앱이 분기에 쓰므로 문자열을 바꾸면 계약이 깨진다.
    static final String JOURNEY_NOT_ACTIVE = "JOURNEY_NOT_ACTIVE";
    static final String STALE_COMMAND_VERSION = "STALE_COMMAND_VERSION";
    static final String STALE_ROUTE_REVISION = "STALE_ROUTE_REVISION";
    static final String NO_BOARDABLE_LEG = "NO_BOARDABLE_LEG";
    static final String NOT_RIDING = "NOT_RIDING";
    static final String NO_ROUTE_OPTION_LEG = "NO_ROUTE_OPTION_LEG";
    static final String UNKNOWN_ROUTE_OPTION = "UNKNOWN_ROUTE_OPTION";
    static final String ROUTE_OPTION_REQUIRED = "ROUTE_OPTION_REQUIRED";
    static final String NOT_SUPPORTED_YET = "NOT_SUPPORTED_YET";

    public JourneyTransition decide(JourneySnapshot journey, JourneyCommand command) {
        JourneyTransition guard = guard(journey, command);
        if (guard != null) {
            return guard;
        }
        return switch (command.type()) {
            case USER_CONFIRMED_BOARDING -> board(journey);
            case USER_CONFIRMED_ALIGHTING -> alight(journey);
            case USER_SELECTED_VEHICLE, USER_CHANGED_VEHICLE -> selectVehicle(journey, command);
            case JOURNEY_CANCELED -> end(JourneyStatus.CANCELED, JourneyEndReason.USER_CANCELED);
            case JOURNEY_COMPLETED -> end(JourneyStatus.COMPLETED, JourneyEndReason.COMPLETED);
            case LEASE_EXPIRED -> end(JourneyStatus.EXPIRED, JourneyEndReason.CLIENT_LEASE_EXPIRED);
            // 근거로만 쓰거나(Phase 3) 아직 없는 기능(Phase 6)이다. 저장은 되고 상태는 그대로다.
            case STOP_PROXIMITY_DETECTED, APP_RESUMED -> JourneyTransition.noChange();
            case REROUTE_ACCEPTED -> JourneyTransition.rejected(NOT_SUPPORTED_YET);
        };
    }

    /**
     * 모든 명령에 공통으로 거는 문지기.
     *
     * <p>낡은 번호로 온 명령은 <b>거절이 아니라 충돌</b>이다. 사용자가 잘못한 것이 아니라 그 사이
     * 상태가 움직인 것이므로, 앱은 최신 상태를 받아 다시 그리면 된다.
     */
    private JourneyTransition guard(JourneySnapshot journey, JourneyCommand command) {
        if (journey.status() != JourneyStatus.ACTIVE) {
            return JourneyTransition.rejected(JOURNEY_NOT_ACTIVE);
        }
        if (command.routeRevision() != journey.routeRevision()) {
            return JourneyTransition.conflict(STALE_ROUTE_REVISION);
        }
        if (command.baseCommandVersion() != journey.commandVersion()) {
            return JourneyTransition.conflict(STALE_COMMAND_VERSION);
        }
        return null;
    }

    // ── B1 탑승 ──────────────────────────────────────────────────────────────

    /**
     * 사용자가 탔다.
     *
     * <p>현재 단계부터 앞으로 훑어 가장 가까운 "기다리기"를 찾는다. 그 사이에 있는 걷기는 함께
     * 완료 처리한다 — 사용자가 걷기를 끝냈다고 따로 눌러 주지 않기 때문이다. 서버가 물어봐야 할
     * 것을 늘리지 않는 편이 낫다.
     *
     * <p>노선 후보가 여럿인데 아직 고르지 않았어도 탑승을 막지 않는다. 막으면 실시간 정보가 없는
     * 구간에서 사용자가 아예 진행할 수 없게 된다. 어느 차를 탔는지는 나중에 B3 로 정정한다.
     */
    private JourneyTransition board(JourneySnapshot journey) {
        int waitIndex = findAhead(journey, leg -> leg.type().isWaiting());
        if (waitIndex < 0) {
            return JourneyTransition.rejected(NO_BOARDABLE_LEG);
        }
        int rideIndex = waitIndex + 1;
        if (rideIndex >= journey.legs().size()
                || !journey.legs().get(rideIndex).type().isRiding()) {
            return JourneyTransition.rejected(NO_BOARDABLE_LEG);
        }

        List<LegStateChange> changes = completeWalksUpTo(journey, waitIndex);
        changes.add(new LegStateChange(waitIndex, JourneyLegState.COMPLETED));
        changes.add(new LegStateChange(rideIndex, JourneyLegState.ACTIVE));

        return applied(changes, rideIndex, null, null, null, List.of());
    }

    // ── B2 하차 ──────────────────────────────────────────────────────────────

    /**
     * 사용자가 내렸다.
     *
     * <p>타고 있는 단계에서만 의미가 있다. 아직 기다리는 중이라면 "내렸다"는 말이 성립하지 않아
     * 거절한다 — 조용히 무시하면 앱은 눌렀는데 아무 일도 안 일어난 것처럼 보인다.
     *
     * <p>탈 것이 더 없으면 여정이 끝난다. 마지막 걷기가 남아 있으면 그 걷기가 다음 단계다.
     */
    private JourneyTransition alight(JourneySnapshot journey) {
        int rideIndex = findAhead(journey, leg -> leg.type().isRiding());
        if (rideIndex < 0 || !isActive(journey, rideIndex)) {
            return JourneyTransition.rejected(NOT_RIDING);
        }

        List<LegStateChange> changes = new ArrayList<>();
        changes.add(new LegStateChange(rideIndex, JourneyLegState.COMPLETED));

        int nextIndex = rideIndex + 1;
        if (nextIndex >= journey.legs().size()) {
            return applied(changes, rideIndex,
                    JourneyStatus.COMPLETED, JourneyEndReason.COMPLETED, null, List.of());
        }
        changes.add(new LegStateChange(nextIndex, JourneyLegState.ACTIVE));
        return applied(changes, nextIndex, null, null, null, List.of());
    }

    // ── B3 차량 선택 ─────────────────────────────────────────────────────────

    /**
     * 탈 노선을 고르거나 바꾼다.
     *
     * <p>후보를 소유한 것은 기다리는 단계지만, 선택은 짝이 되는 타는 단계에도 함께 적용한다.
     * 한쪽만 바꾸면 "기다릴 때는 370, 탈 때는 721" 같은 상태가 생긴다.
     *
     * <p>이미 타고 있는 중에도 허용한다. 잘못 탄 것을 알아차리는 시점이 대개 탄 뒤이기 때문이다.
     */
    private JourneyTransition selectVehicle(JourneySnapshot journey, JourneyCommand command) {
        if (command.routeOptionId() == null) {
            return JourneyTransition.rejected(ROUTE_OPTION_REQUIRED);
        }
        int waitIndex = findWaitLegOwningOptions(journey);
        if (waitIndex < 0) {
            return JourneyTransition.rejected(NO_ROUTE_OPTION_LEG);
        }
        boolean known = journey.legs().get(waitIndex).routeOptions().stream()
                .map(JourneyRouteOptionView::optionId)
                .anyMatch(id -> id.equals(command.routeOptionId()));
        if (!known) {
            return JourneyTransition.rejected(UNKNOWN_ROUTE_OPTION);
        }

        List<Integer> targets = new ArrayList<>(List.of(waitIndex));
        int rideIndex = waitIndex + 1;
        if (rideIndex < journey.legs().size() && journey.legs().get(rideIndex).type().isRiding()) {
            targets.add(rideIndex);
        }
        return applied(List.of(), null, null, null, command.routeOptionId(), targets);
    }

    /**
     * 지금 선택을 받을 기다리기 단계.
     *
     * <p>세 가지 경우를 모두 같은 곳으로 보낸다.
     * <ul>
     *   <li>기다리는 중 — 그 단계</li>
     *   <li>타고 있는 중 — 방금 지나온 짝 단계. 잘못 탄 것을 정정하는 경우다</li>
     *   <li>환승 도보 중 — 앞에 있는 다음 기다리기 단계</li>
     * </ul>
     *
     * <p>세 번째를 빠뜨리면 안 된다. 내려서 다음 정류장으로 걸어가는 동안이 "어느 버스를 탈지"를
     * 고르는 가장 자연스러운 시점이다.
     */
    private int findWaitLegOwningOptions(JourneySnapshot journey) {
        int current = journey.currentLegIndex();
        List<JourneyLegView> legs = journey.legs();
        if (current < legs.size() && legs.get(current).type().isRiding()
                && current > 0 && legs.get(current - 1).type().isWaiting()) {
            return current - 1;
        }
        return findAhead(journey, leg -> leg.type().isWaiting());
    }

    // ── 종료 ─────────────────────────────────────────────────────────────────

    /**
     * 여정을 끝낸다.
     *
     * <p>남은 단계의 상태는 건드리지 않는다. "어디까지 갔다가 접었는지"가 그대로 남아야 나중에
     * 왜 끝났는지 따질 수 있다.
     */
    private JourneyTransition end(JourneyStatus status, JourneyEndReason reason) {
        return applied(List.of(), null, status, reason, null, List.of());
    }

    // ── 보조 ─────────────────────────────────────────────────────────────────

    /** 현재 단계부터 앞으로 훑어 조건에 맞는 첫 단계를 찾는다. 뒤로는 보지 않는다. */
    private int findAhead(JourneySnapshot journey, java.util.function.Predicate<JourneyLegView> match) {
        List<JourneyLegView> legs = journey.legs();
        for (int i = Math.max(0, journey.currentLegIndex()); i < legs.size(); i++) {
            if (match.test(legs.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 현재 단계와 목표 사이의 걷기를 완료로 바꾼다. */
    private List<LegStateChange> completeWalksUpTo(JourneySnapshot journey, int targetIndex) {
        List<LegStateChange> changes = new ArrayList<>();
        for (int i = Math.max(0, journey.currentLegIndex()); i < targetIndex; i++) {
            JourneyLegView leg = journey.legs().get(i);
            if (leg.state() == JourneyLegState.COMPLETED) {
                continue;
            }
            changes.add(new LegStateChange(i, leg.type() == JourneyLegType.WALK
                    || leg.type() == JourneyLegType.TRANSFER_WALK
                    ? JourneyLegState.COMPLETED
                    : JourneyLegState.SKIPPED));
        }
        return changes;
    }

    private boolean isActive(JourneySnapshot journey, int legIndex) {
        return journey.legs().get(legIndex).state() == JourneyLegState.ACTIVE;
    }

    private JourneyTransition applied(List<LegStateChange> changes, Integer currentLegIndex,
                                      JourneyStatus status, JourneyEndReason endReason,
                                      Long routeOptionId, List<Integer> targets) {
        return new JourneyTransition(JourneyEventResult.APPLIED, null, changes,
                currentLegIndex, status, endReason, routeOptionId, targets, true);
    }
}
