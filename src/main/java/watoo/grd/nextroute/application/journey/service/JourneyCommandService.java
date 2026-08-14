package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyEventOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.JourneyTransition;
import watoo.grd.nextroute.application.journey.dto.LegStateChange;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingAbandonReason;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.domain.journey.entity.JourneyEvent;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneyEventRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.RerouteProposalRepository;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

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

    private static final String EVENT_ID_REUSED = "EVENT_ID_REUSED";

    private final JourneySessionRepository sessionRepository;
    private final SubwayOfflineProtectionPolicy subwayProtectionPolicy;
    private final JourneyLegRepository legRepository;
    private final JourneyEventRepository eventRepository;
    private final JourneyNotificationRepository notificationRepository;
    private final RerouteProposalRepository rerouteProposalRepository;
    private final JourneyRerouteMetrics rerouteMetrics;
    private final SubwayBindingLifecycleService subwayBindingLifecycle;
    private final JourneyStateMachine stateMachine;
    private final JourneyQueryService queryService;
    private final Clock clock;

    @Override
    @Transactional
    public JourneyEventOutcome apply(Long userId, Long journeyId, JourneyCommand command) {
        JourneySession journey = sessionRepository.findByIdAndUserIdForUpdate(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));

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
     * <p>지하철에서 응답을 못 받은 앱은 같은 leg 전이 명령을 다시 보낸다. 이를
     * 새 명령으로 판정하면 앱은 반영된 상태를 실패로 오해한다. 처음 결과를 재생해
     * 앱과 서버를 같은 canonical 상태로 수렴시킨다.
     */
    private JourneyEventOutcome replayIfSeen(JourneyCommand command, JourneySession journey) {
        return eventRepository.findByEventId(command.eventId())
                .map(seen -> {
                    if (!sameCommand(seen, command, journey.getId())) {
                        return new JourneyEventOutcome(JourneyEventResult.CONFLICT,
                                EVENT_ID_REUSED, queryService.snapshotOf(journey));
                    }
                    JourneyEventResult replayResult = seen.getResult() == JourneyEventResult.APPLIED
                            ? JourneyEventResult.DUPLICATE : seen.getResult();
                    return new JourneyEventOutcome(replayResult, seen.getRejectionReason(),
                            queryService.snapshotOf(journey));
                })
                .orElse(null);
    }

    /** eventId는 요청 내용까지 같은 재전송에만 재사용할 수 있다. */
    private boolean sameCommand(JourneyEvent seen, JourneyCommand command, Long journeyId) {
        return seen.getJourneyId().equals(journeyId)
                && seen.getBaseCommandVersion() == command.baseCommandVersion()
                && seen.getRouteRevision() == command.routeRevision()
                && seen.getType().equals(command.type().name())
                && Objects.equals(seen.getClientOccurredAt(), command.clientOccurredAt())
                && Objects.equals(seen.getPayload(), transitionPayload(command));
    }

    /** 결정된 지시서를 여정·단계에 옮긴다. */
    private void applyTransition(JourneySession journey, JourneyTransition transition) {
        Instant appliedAt = Instant.now(clock);
        Map<Integer, JourneyLeg> legsByIndex = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                        journey.getId(), journey.getRouteRevision())
                .stream()
                .collect(Collectors.toMap(JourneyLeg::getLegIndex, Function.identity()));

        for (LegStateChange change : transition.legStateChanges()) {
            JourneyLeg leg = legsByIndex.get(change.legIndex());
            if (leg != null) {
                leg.changeState(change.state(), appliedAt);
            }
        }
        if (transition.newCurrentLegIndex() != null) {
            journey.moveToLeg(transition.newCurrentLegIndex());
            // 지하철에 들어가면 보호 시각을 세우고, 벗어나면 지운다. 계산은 정책이 소유한다.
            subwayProtectionPolicy.apply(journey,
                    legsByIndex.get(transition.newCurrentLegIndex()), null, appliedAt);
            // 단계가 바뀌면 상황이 크게 달라진다. 다음 평가 주기를 기다리지 않는다.
            journey.scheduleEvaluationAt(appliedAt);
        }
        if (transition.newStatus() != null) {
            journey.end(transition.newStatus(), transition.endReason(), appliedAt);
            // 끝난 여정의 알림은 나가면 안 된다. 사용자 취소·완료·앱이 보고한 lease 만료가
            // 모두 여기로 모이므로 취소도 여기 한 자리에서 한다.
            //
            // 스위치를 걸지 않는다. 평가기를 껐다 켜는 사이 남은 알림이 나중에 나가면
            // 사용자는 이미 끝난 여정의 안내를 받는다.
            notificationRepository.cancelPendingByJourneyId(journey.getId());
            // 제안도 같이 접는다. 남겨 두면 수락 API 가 "여정은 끝났는데 제안은 유효한" 상태를
            // 본다. 알림과 마찬가지로 스위치를 걸지 않는다 - 재탐색을 끈 뒤에도 그전에 만들어진
            // 행은 여정이 끝나면 접혀야 한다.
            rerouteMetrics.recordResolved("journey-ended",
                    rerouteProposalRepository.resolveActiveByJourneyId(journey.getId(), appliedAt));
            // 미완료 지하철 고정도 여기서 접는다. 끝난 여정의 PENDING 을 남기면 판정기가 그 행을
            // 영원히 재처리한다 - 이 표는 행을 지우지 않아 스스로 멈추지 않는다.
            subwayBindingLifecycle.abandonOpen(journey.getId(), SubwayBindingAbandonReason.ENDED);
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
                .clientOccurredAt(command.clientOccurredAt())
                .serverReceivedAt(Instant.now(clock))
                .payload(transitionPayload(command))
                .result(transition.result())
                .resultingCommandVersion(resultingCommandVersion)
                .rejectionReason(transition.rejectionReason())
                .build());
    }

    private com.fasterxml.jackson.databind.JsonNode transitionPayload(JourneyCommand command) {
        if (command.fromLegIndex() == null && command.toLegIndex() == null) {
            return null;
        }
        com.fasterxml.jackson.databind.node.ObjectNode payload =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (command.fromLegIndex() != null) {
            payload.put("fromLegIndex", command.fromLegIndex());
        }
        if (command.toLegIndex() != null) {
            payload.put("toLegIndex", command.toLegIndex());
        }
        return payload;
    }
}
