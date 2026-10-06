package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteBoardSlot;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateSummary;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.port.out.NotificationChannelResolver;
import watoo.grd.nextroute.application.journey.port.out.RerouteCooldownPort;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposal;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalStatus;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.RerouteProposalRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 보드 갱신 뒤 push 를 보낸다(#264 PR-5).
 *
 * <p>순서: 판단({@link RerouteBoardPushPolicy}) → 직전 push 무시 집계 → 무시 상한 → cooldown → 채널 →
 * 한 트랜잭션에서 [push 순번 CAS + outbox 적재 + 대기 중인 환승 위험 알림 취소] → relay 깨우기.
 *
 * <p><b>무시는 push 단위로 센다.</b> 다음 push 를 보낼 때가 됐는데 직전 push 에 답(경로 조회·무시·거절·
 * 수락)이 없으면 그 push 는 무시된 것이다. 보드는 갱신되며 만료되지 않으므로 "알린 제안이 답 없이 만료"
 * 방식으로는 셀 수 없다. {@code /active} 폴링은 답으로 치지 않는다 — 앱이 자동으로 부른다.
 *
 * <p>예외를 삼킨다. push 가 실패해도 보드 갱신은 성공이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerouteBoardPushService {

    private static final EnumSet<TransferStatus> RISK = EnumSet.of(TransferStatus.AT_RISK, TransferStatus.MISSED);

    private final JourneySessionRepository sessionRepository;
    private final RerouteProposalRepository proposalRepository;
    private final TransferConnectionRepository transferRepository;
    private final RerouteBoardPushTxService pushTxService;
    private final RerouteCooldownPort cooldownPort;
    private final NotificationChannelResolver channelResolver;
    private final JourneyRelayWaker relayWaker;
    private final JourneyRerouteProperties properties;
    private final JourneyRerouteMetrics metrics;
    private final Clock clock;

    public void considerPush(Long journeyId, List<RerouteBoardSlot> slots, Instant now) {
        try {
            attempt(journeyId, slots, now);
        } catch (Exception e) {
            metrics.recordAlert("failed");
            log.warn("[RerouteBoard] push 판단 실패 journeyId={}: {}", journeyId, e.getMessage());
        }
    }

    private void attempt(Long journeyId, List<RerouteBoardSlot> slots, Instant now) {
        if (!properties.isEnabled() || !properties.getAlert().isEnabled()) {
            return;
        }
        Optional<JourneySession> found = sessionRepository.findById(journeyId)
                .filter(journey -> journey.getStatus() == JourneyStatus.ACTIVE);
        Optional<RerouteProposal> board = proposalRepository
                .findFirstByJourneyIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
                        journeyId, RerouteProposalStatus.ACTIVE)
                .filter(RerouteProposal::isBoard);
        if (found.isEmpty() || board.isEmpty()) {
            return;
        }
        JourneySession journey = found.get();
        Optional<RerouteBoardPushPolicy.Decision> decision = RerouteBoardPushPolicy.decide(slots,
                new RerouteBoardPushPolicy.LastPush(board.get().getLastPushSlot(),
                        board.get().getLastPushRoute(), board.get().getLastPushGain()),
                transferAtRisk(journey), journey.getStartedAt(),
                sessionRepository.findRerouteAcceptedAt(journeyId).orElse(null), now,
                properties);
        if (decision.isEmpty()) {
            return;
        }
        if (pushTxService.countIgnoredPush(journey.getId())) {
            // 직전 push 를 무시로 셌다. 상한 판단은 갱신된 값으로 한다.
            journey = sessionRepository.findById(journeyId).orElse(journey);
        }
        if (!journey.isRerouteAlertAllowed(properties.getAlert().getMaxIgnored())) {
            metrics.recordAlert(journey.isRerouteTerminated() ? "terminated" : "ignore-limit");
            return;
        }
        if (!cooldownPort.tryAdmit(journey.getId(), journey.getRouteRevision(), RerouteCooldownPort.Scope.ALERT)) {
            metrics.recordAlert("cooldown");
            return;
        }
        List<NotificationChannel> channels = channelResolver.resolve(journey.getUserId());
        if (channels.isEmpty()) {
            metrics.recordAlert("no-channel");
            return;
        }
        if (pushTxService.publish(board.get(), journey, decision.get(), channels, payload(board.get(), decision.get()), now)) {
            relayWaker.wake();
            metrics.recordAlert("published");
            log.info("[RerouteBoard] push journeyId={} kind={} slot={} gain={}s", journeyId,
                    decision.get().kind().tag(), decision.get().slot().slotKey(),
                    decision.get().slot().lastGainSec());
        } else {
            metrics.recordAlert("duplicate");
        }
    }

    private boolean transferAtRisk(JourneySession journey) {
        return transferRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                        journey.getId(), journey.getRouteRevision())
                .stream()
                .filter(transfer -> transfer.getToLegIndex() >= journey.getCurrentLegIndex())
                .findFirst()
                .map(transfer -> RISK.contains(transfer.getStatus()))
                .orElse(false);
    }

    /**
     * 문구 재료. 후보 목록은 싣지 않는다 — 누르면 앱이 최신 보드를 다시 받는다(정리본 §6).
     *
     * <p>노선 이름은 화면 요약 첫 줄("{노선} 승차")에서 꺼낸다. 화면 값에 노선 칸이 따로 없다.
     */
    private Map<String, Object> payload(RerouteProposal board, RerouteBoardPushPolicy.Decision decision) {
        RerouteBoardSlot slot = decision.slot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proposalId", board.getId());
        body.put("etaGainSec", slot.lastGainSec());
        body.put("pushKind", decision.kind().tag());
        body.put("originType", RerouteCandidateSummary.originType(slot.originKind()));
        if (slot.candidate() != null && slot.candidate().display().alight() != null) {
            body.put("stopName", slot.candidate().display().alight().name());
        }
        if (slot.candidate() != null && slot.candidate().display().legSummaries() != null
                && !slot.candidate().display().legSummaries().isEmpty()) {
            String first = slot.candidate().display().legSummaries().get(0);
            if (first != null && first.endsWith(" 승차")) {
                body.put("routeName", first.substring(0, first.length() - " 승차".length()));
            }
        }
        return body;
    }
}
