package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyEventOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.JourneyTransition;
import watoo.grd.nextroute.application.journey.dto.LegStateChange;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.domain.journey.entity.JourneyEvent;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneyEventRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 여정에 온 명령을 실제로 반영한다.
 *
 * <p>전이 규칙은 {@link JourneyStateMachine} 이 정하고, 여기서는 그 결정을 DB 에 옮긴다.
 * 두 일을 나눈 덕분에 "규칙이 맞는가"와 "저장이 맞는가"를 따로 확인할 수 있다.
 *
 * <p>한 트랜잭션 안에서 <b>여정 잠금 → 재전송 확인 → 결정 → 반영 → 이력 기록</b>이 모두 끝난다.
 * 나눠 놓으면 두 요청이 같은 상태를 읽고 둘 다 반영하는 창이 생긴다.
 */
@Service
@RequiredArgsConstructor
public class JourneyCommandService implements ApplyJourneyEventUseCase {

    private final JourneySessionRepository sessionRepository;
    private final JourneyLegRepository legRepository;
    private final JourneyEventRepository eventRepository;
    private final JourneyStateMachine stateMachine;
    private final JourneyQueryService queryService;
    private final Clock clock;

    @Override
    @Transactional
    public JourneyEventOutcome apply(Long userId, Long journeyId, JourneyCommand command) {
        JourneySession journey = sessionRepository.findByIdAndUserIdForUpdate(journeyId, userId)
                .orElseThrow(() -> new NoSuchElementException("여정을 찾을 수 없다: " + journeyId));

        JourneyEventOutcome replayed = replayIfSeen(command, journey);
        if (replayed != null) {
            return replayed;
        }

        JourneySnapshot before = queryService.snapshotOf(journey);
        JourneyTransition transition = stateMachine.decide(before, command);

        if (!transition.isApplied()) {
            record(command, journey, transition, null);
            return new JourneyEventOutcome(transition.result(), transition.rejectionReason(),
                    // 거절·충돌일수록 최신 상태가 더 필요하다. 앱은 이걸로 화면을 다시 그린다.
                    before);
        }

        applyTransition(journey, transition);
        record(command, journey, transition,
                transition.commandVersionIncrement() ? journey.getCommandVersion() : null);

        return new JourneyEventOutcome(JourneyEventResult.APPLIED, null,
                queryService.snapshotOf(journey));
    }

    /**
     * 이미 처리한 명령이면 처음 결과를 그대로 돌려준다.
     *
     * <p>지하철에서 응답을 못 받은 앱은 같은 명령을 다시 보낸다. 그때 "이미 탔는데 또 탔다고?"로
     * 거절해 버리면, 사용자 입장에서는 눌렀는데 실패한 것으로 보인다. 처음 판정을 그대로 재생하는
     * 편이 앱과 서버 사이에 오해를 남기지 않는다.
     */
    private JourneyEventOutcome replayIfSeen(JourneyCommand command, JourneySession journey) {
        return eventRepository.findByEventId(command.eventId())
                .map(seen -> new JourneyEventOutcome(
                        // 결과 자체는 DUPLICATE 로 알린다. 앱이 재전송이었음을 알 수 있어야 한다.
                        JourneyEventResult.DUPLICATE,
                        seen.getRejectionReason(),
                        queryService.snapshotOf(journey)))
                .orElse(null);
    }

    /** 결정된 지시서를 여정·단계에 옮긴다. */
    private void applyTransition(JourneySession journey, JourneyTransition transition) {
        Map<Integer, JourneyLeg> legsByIndex = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                        journey.getId(), journey.getRouteRevision())
                .stream()
                .collect(Collectors.toMap(JourneyLeg::getLegIndex, Function.identity()));

        for (LegStateChange change : transition.legStateChanges()) {
            JourneyLeg leg = legsByIndex.get(change.legIndex());
            if (leg != null) {
                leg.changeState(change.state());
            }
        }
        for (Integer legIndex : transition.routeOptionTargetLegs()) {
            JourneyLeg leg = legsByIndex.get(legIndex);
            if (leg != null) {
                leg.selectRouteOption(transition.selectedRouteOptionId());
            }
        }
        if (transition.newCurrentLegIndex() != null) {
            journey.moveToLeg(transition.newCurrentLegIndex());
        }
        if (transition.newStatus() != null) {
            journey.end(transition.newStatus(), transition.endReason(), Instant.now(clock));
        }
        if (transition.commandVersionIncrement()) {
            journey.bumpCommandVersion();
        }
        legRepository.flush();
        sessionRepository.flush();
    }

    /**
     * 명령을 이력에 남긴다. 거절과 충돌도 남긴다.
     *
     * <p>남기지 않으면 재전송이 왔을 때 처음 판정을 알 수 없고, "왜 그때 안 먹었나"를 나중에
     * 물을 수도 없다.
     */
    private void record(JourneyCommand command, JourneySession journey,
                        JourneyTransition transition, Long resultingCommandVersion) {
        eventRepository.saveAndFlush(JourneyEvent.builder()
                .eventId(command.eventId())
                .journeyId(journey.getId())
                .baseCommandVersion(command.baseCommandVersion())
                .routeRevision(command.routeRevision())
                .type(command.type().name())
                .serverReceivedAt(Instant.now(clock))
                .result(transition.result())
                .resultingCommandVersion(resultingCommandVersion)
                .rejectionReason(transition.rejectionReason())
                .build());
    }
}
