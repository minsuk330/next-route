package watoo.grd.nextroute.application.journey.service;

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
}
