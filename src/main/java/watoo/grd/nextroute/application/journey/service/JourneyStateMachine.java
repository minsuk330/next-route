package watoo.grd.nextroute.application.journey.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyLegView;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.JourneyTransition;
import watoo.grd.nextroute.application.journey.dto.LegStateChange;
import watoo.grd.nextroute.domain.journey.entity.JourneyEndReason;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;

import java.util.List;

/** GPS progress engine이 확정한 leg 경계를 canonical Journey 상태에 반영한다. */
@Component
public class JourneyStateMachine {

    static final String JOURNEY_NOT_ACTIVE = "JOURNEY_NOT_ACTIVE";
    static final String STALE_COMMAND_VERSION = "STALE_COMMAND_VERSION";
    static final String STALE_ROUTE_REVISION = "STALE_ROUTE_REVISION";
    static final String INVALID_LEG_TRANSITION_PAYLOAD = "INVALID_LEG_TRANSITION_PAYLOAD";
    static final String EXPECTED_LEG_MISMATCH = "EXPECTED_LEG_MISMATCH";
    static final String INVALID_TRANSITION = "INVALID_TRANSITION";
    static final String NOT_SUPPORTED_YET = "NOT_SUPPORTED_YET";

    public JourneyTransition decide(JourneySnapshot journey, JourneyCommand command) {
        JourneyTransition guard = guard(journey, command);
        if (guard != null) {
            return guard;
        }
        return switch (command.type()) {
            case CLIENT_LEG_TRANSITION_DETECTED -> advanceLeg(journey, command);
            case JOURNEY_CANCELED -> end(JourneyStatus.CANCELED,
                    JourneyEndReason.USER_CANCELED, List.of());
            case JOURNEY_COMPLETED -> complete(journey);
            case LEASE_EXPIRED -> end(JourneyStatus.EXPIRED,
                    JourneyEndReason.CLIENT_LEASE_EXPIRED, List.of());
            // 수락은 전용 엔드포인트가 받는다(Phase 6 ⑤, RerouteAcceptTxService). 여기로 오는
            // 것은 이벤트 통로로 보낸 앱이고, 그 통로는 열지 않는다 — 이벤트는 "앱이 관측한
            // 사실"이고 수락은 "사용자의 선택"이라 재전송 규칙도 되짚을 조건도 다르다.
            case REROUTE_ACCEPTED -> JourneyTransition.rejected(NOT_SUPPORTED_YET);
        };
    }

    private JourneyTransition guard(JourneySnapshot journey, JourneyCommand command) {
        if (journey.status() != JourneyStatus.ACTIVE) {
            return JourneyTransition.conflict(JOURNEY_NOT_ACTIVE);
        }
        if (command.routeRevision() != journey.routeRevision()) {
            return JourneyTransition.conflict(STALE_ROUTE_REVISION);
        }
        if (command.baseCommandVersion() != journey.commandVersion()) {
            return JourneyTransition.conflict(STALE_COMMAND_VERSION);
        }
        return null;
    }

    private JourneyTransition advanceLeg(JourneySnapshot journey, JourneyCommand command) {
        Integer from = command.fromLegIndex();
        Integer to = command.toLegIndex();
        if (from == null || to == null || from < 0 || to < 0) {
            return JourneyTransition.rejected(INVALID_LEG_TRANSITION_PAYLOAD);
        }
        if (from != journey.currentLegIndex()) {
            return JourneyTransition.conflict(EXPECTED_LEG_MISMATCH);
        }
        if (to != from + 1 || to >= journey.legs().size()) {
            return JourneyTransition.conflict(INVALID_TRANSITION);
        }

        JourneyLegView current = journey.legs().get(from);
        JourneyLegView next = journey.legs().get(to);
        if (current.state() != JourneyLegState.ACTIVE
                || next.state() != JourneyLegState.PENDING) {
            return JourneyTransition.conflict(INVALID_TRANSITION);
        }

        return new JourneyTransition(
                JourneyEventResult.APPLIED,
                null,
                List.of(
                        new LegStateChange(from, JourneyLegState.COMPLETED),
                        new LegStateChange(to, JourneyLegState.ACTIVE)),
                to,
                null,
                null,
                true);
    }

    private JourneyTransition complete(JourneySnapshot journey) {
        List<LegStateChange> changes = journey.currentLeg() == null
                ? List.of()
                : List.of(new LegStateChange(journey.currentLegIndex(), JourneyLegState.COMPLETED));
        return end(JourneyStatus.COMPLETED, JourneyEndReason.COMPLETED, changes);
    }

    private JourneyTransition end(JourneyStatus status, JourneyEndReason reason,
                                  List<LegStateChange> changes) {
        return new JourneyTransition(
                JourneyEventResult.APPLIED,
                null,
                changes,
                null,
                status,
                reason,
                true);
    }
}
