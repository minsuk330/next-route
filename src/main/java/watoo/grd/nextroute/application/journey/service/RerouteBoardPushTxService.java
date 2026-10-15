package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.application.journey.port.out.PushMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteBoardSlot;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposal;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.RerouteProposalRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 보드 push 의 쓰기(#264 PR-5). 판단({@link RerouteBoardPushService})과 빈을 나눈 이유 — 같은 빈 안에서
 * 부르면 {@code @Transactional} 프록시를 지나지 않아 트랜잭션이 열리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RerouteBoardPushTxService {

    private static final List<JourneyCheckpoint> RISK_CHECKPOINTS =
            List.of(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, JourneyCheckpoint.TRANSFER_RISK_MISSED);

    private final RerouteProposalRepository proposalRepository;
    private final JourneySessionRepository sessionRepository;
    private final JourneyNotificationRepository notificationRepository;
    private final JourneyNotificationEnqueuer enqueuer;
    private final JourneyRerouteProperties properties;
    private final JourneyRerouteMetrics metrics;
    private final Clock clock;
    private final RouteAlertBudget alertBudget;

    /** 직전 push 에 답이 없었으면 무시 1회로 센다. 같은 push 를 두 번 세지 않게 답한 것으로 닫는다. */
    @Transactional
    public boolean countIgnoredPush(Long journeyId) {
        if (proposalRepository.answerPush(journeyId) == 1) {
            sessionRepository.bumpRerouteIgnored(journeyId, 1);
            metrics.recordAlert("unanswered");
            return true;
        }
        return false;
    }

    /** push 순번 CAS → outbox 적재 → 대기 중인 환승 위험 알림 취소. 순번 경합에 지면 아무것도 쓰지 않는다. */
    @Transactional
    public boolean publish(RerouteProposal board, JourneySession journey,
                           RerouteBoardPushPolicy.Decision decision, List<NotificationChannel> channels,
                           Map<String, Object> payload, Instant now) {
        if (alertBudget.enabled()) {
            return publishWithBudget(board, journey, decision, channels, payload, now);
        }
        RerouteBoardSlot slot = decision.slot();
        int seq = board.getPushSeq();
        if (proposalRepository.recordPush(board.getId(), seq, slot.slotKey(), slot.routeFingerprint(),
                slot.lastGainSec(), now, LocalDateTime.now(clock)) != 1) {
            return false;
        }
        Instant expiresAt = now.plus(properties.getAlert().getSnapshotTtl());
        boolean enqueued = false;
        for (NotificationChannel channel : channels) {
            enqueued |= enqueuer.enqueueBoardPush(journey.getId(), journey.getRouteRevision(), board.getId(),
                    seq + 1, payload, channel, now, expiresAt);
        }
        if (enqueued) {
            // 대안이 나갔으니 아직 안 나간 환승 위험 알림은 거둔다 — 사용자는 알림 하나만 받는다.
            int canceled = notificationRepository.cancelPendingByCheckpoints(journey.getId(),
                    journey.getRouteRevision(), RISK_CHECKPOINTS);
            if (canceled > 0) {
                metrics.recordAlert("merged-risk", canceled);
            }
        }
        return enqueued;
    }

    /**
     * 피로 정책(#428 PR-1b)이 켜졌을 때의 push. 순서 = 여정 행 잠금 → 순번 CAS → 위험 알림 취소 → 판정 → 삽입.
     *
     * <p>여정 행을 <b>가장 먼저</b> 잠근다. 평가기는 같은 행을 잠근 채 위험 알림을 쌓으므로, 둘이 동시에 돌아도
     * 한 줄로 서서 울릴 권리를 하나만 가져간다. 순번 CAS(reroute_proposal)보다 앞이어야 잠금 순서가 모든 경로에서
     * journey_session → reroute_proposal → journey_notification 으로 같다.
     *
     * <p>위험 알림 취소를 판정 <b>앞</b>으로 옮겼다(정책 이전엔 삽입 뒤). 합치기 창에 걸려 아직 안 나간 위험 알림이
     * 권리를 쥐고 있으면, 그것을 취소한 이 push 가 권리를 물려받아 울린다 — "위험 + 대안"이 한 번에 나간다. 삽입이
     * 0행이면(여정이 끝났거나 경로가 바뀌었다) 취소한 위험 알림도 어차피 relay 가 버릴 행이다.
     */
    private boolean publishWithBudget(RerouteProposal board, JourneySession journey,
                                      RerouteBoardPushPolicy.Decision decision, List<NotificationChannel> channels,
                                      Map<String, Object> payload, Instant now) {
        alertBudget.lockJourney(journey.getId());
        RerouteBoardSlot slot = decision.slot();
        int seq = board.getPushSeq();
        if (proposalRepository.recordPush(board.getId(), seq, slot.slotKey(), slot.routeFingerprint(),
                slot.lastGainSec(), now, LocalDateTime.now(clock)) != 1) {
            return false;
        }
        int revision = journey.getRouteRevision();
        List<String> canceled = notificationRepository.cancelPendingByCheckpointsReturningKeys(journey.getId(),
                revision, RISK_CHECKPOINTS.stream().map(Enum::name).toList());
        if (!canceled.isEmpty()) {
            metrics.recordAlert("merged-risk", canceled.size());
        }
        String logicalKey = JourneyNotificationKeys.boardPush(journey.getId(), revision, board.getId(), seq + 1);
        PushMessage.Delivery delivery = alertBudget.decide(journey.getId(), revision, logicalKey, canceled);

        Instant expiresAt = now.plus(properties.getAlert().getSnapshotTtl());
        boolean enqueued = false;
        for (NotificationChannel channel : channels) {
            enqueued |= enqueuer.enqueueBoardPush(journey.getId(), revision, board.getId(),
                    seq + 1, payload, channel, now, expiresAt, delivery);
        }
        if (!enqueued && delivery == PushMessage.Delivery.ALERTING) {
            // 울릴 알림이 안 생겼다. 권리를 쥔 채 두면 이 경로 버전은 다시 못 울린다.
            alertBudget.release(journey.getId(), logicalKey);
        }
        return enqueued;
    }
}
