package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteContext;
import watoo.grd.nextroute.application.journey.dto.RerouteProposalView;
import watoo.grd.nextroute.application.journey.dto.RerouteTriggerSignal;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.port.out.NotificationChannelResolver;
import watoo.grd.nextroute.application.journey.port.out.RerouteCooldownPort;
import watoo.grd.nextroute.application.journey.service.RerouteProposalService.Proposed;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 서버가 <b>먼저</b> 알린다 — 재탐색 알림의 발행 경로(journey-reroute-nextstop PR-3).
 *
 * <h2>바뀌는 계약</h2>
 * 지금까지 제안은 사용자가 "다시 찾아줘"를 눌러야만 생겼다(pull). 이 클래스부터는 서버가 환승
 * 위험을 감지하면 스스로 찾아 push 한 줄로 알린다. <b>바꾸는 것은 여전히 사용자만 한다</b> —
 * 자동 적용은 없고, 알림은 "목록을 열어 보라"는 신호일 뿐이다.
 *
 * <h2>알리지 않을 이유를 먼저 본다</h2>
 * 순서가 계약이다. 싼 판단이 앞이고, 사용자의 의사표시가 기계적 판단보다 세다.
 * <ol>
 *   <li>스위치 — 기능·알림이 꺼져 있다</li>
 *   <li>여정 상태 — 수락했거나 거절했다({@code terminated}), 무응답 상한을 넘었다</li>
 *   <li>cooldown — 방금 알렸다(5분). <b>후보를 찾기 전</b>에 본다: 어차피 안 보낼 알림 때문에
 *       OTP·실시간·ML 을 부를 이유가 없다</li>
 *   <li>후보 — 찾아봤지만 나은 길이 없다</li>
 *   <li>문턱 — 개선폭이 사용자를 깨울 만큼 크지 않다</li>
 *   <li>리드타임 — 최선 후보의 하차점이 이미 코앞이다. 알림을 읽을 시간이 없다</li>
 *   <li>채널 — 보낼 길이 없다(push 토큰 0건)</li>
 * </ol>
 *
 * <p><b>예외를 밖으로 내보내지 않는다.</b> 부르는 자리가 주기 평가라, 여기서 던지면 이미 커밋된
 * 평가가 실패로 기록된다({@link RerouteTriggerEvaluator} 와 같은 이유).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerouteAlertPublisher {

    private final RerouteProposalService proposalService;
    private final RerouteAlertTxService alertTxService;
    private final JourneySessionRepository sessionRepository;
    private final RerouteCooldownPort cooldownPort;
    private final NotificationChannelResolver channelResolver;
    private final JourneyRelayWaker relayWaker;
    private final JourneyRerouteProperties properties;
    private final JourneyRerouteMetrics metrics;

    /**
     * 방아쇠 하나를 알림까지 밀어 본다.
     *
     * @param userId 평가 컨텍스트가 이미 들고 있는 소유자. 여기서 다시 조회하지 않는다
     */
    public void publish(RerouteTriggerSignal signal, Long userId, Instant now) {
        try {
            attempt(signal, userId, now);
        } catch (Exception e) {
            metrics.recordAlert("failed");
            log.warn("[JourneyReroute] 알림 발행 실패 journeyId={}: {}",
                    signal.journeyId(), e.getMessage());
        }
    }

    private void attempt(RerouteTriggerSignal signal, Long userId, Instant now) {
        if (!properties.isEnabled() || !properties.getAlert().isEnabled()) {
            metrics.recordAlert("disabled");
            return;
        }
        Optional<JourneySession> found = sessionRepository
                .findByIdAndUserIdAndDeletedAtIsNull(signal.journeyId(), userId);
        if (found.isEmpty() || found.get().getStatus() != JourneyStatus.ACTIVE) {
            metrics.recordAlert("journey-missing");
            return;
        }
        JourneySession journey = found.get();
        if (!journey.isRerouteAlertAllowed(properties.getAlert().getMaxIgnored())) {
            // 사용자가 이미 답했다(수락·거절) 또는 두 번 무시했다. 더 울리지 않는다.
            metrics.recordAlert(journey.isRerouteTerminated() ? "terminated" : "ignore-limit");
            return;
        }
        if (!cooldownPort.tryAdmit(journey.getId(), journey.getRouteRevision(),
                RerouteCooldownPort.Scope.ALERT)) {
            metrics.recordAlert("cooldown");
            return;
        }

        RerouteContext context = new RerouteContext(journey.getId(), userId,
                journey.getRouteRevision(), journey.getCommandVersion(),
                signal.transferIndex(), now);
        Optional<Proposed> proposed = proposalService.collect(context);
        if (proposed.isEmpty()) {
            metrics.recordAlert("no-candidate");
            return;
        }
        List<RerouteCandidate> candidates = proposed.get().candidates();
        if (!worthWaking(candidates, now)) {
            return;
        }

        List<NotificationChannel> channels = channelResolver.resolve(userId);
        if (channels.isEmpty()) {
            // 보낼 길이 없다. 제안을 저장하지 않는 이유 — 여정당 하나뿐인 자리를, 아무도 보지
            // 못할 제안이 5분 동안 막는다.
            metrics.recordAlert("no-channel");
            return;
        }

        Optional<RerouteProposalView> stored = proposalService.store(context, proposed.get(),
                now.plus(properties.getAlert().getSnapshotTtl()));
        if (stored.isEmpty()) {
            // 후보를 만드는 사이 여정이 움직였다. 저장 쪽이 이미 사유를 셌다.
            return;
        }

        boolean alerted = alertTxService.alert(stored.get(), gainOf(candidates), channels, now);
        if (!alerted) {
            metrics.recordAlert("duplicate");
            return;
        }
        // 커밋된 뒤에 깨운다. relay 는 표를 보고 일하므로 신호가 먼저 가면 헛돈다.
        relayWaker.wake();
        metrics.recordAlert("published");
        log.info("[JourneyReroute] 재탐색 알림 journeyId={} proposalId={} gain={}s",
                journey.getId(), stored.get().proposalId(), gainOf(candidates));
    }

    /**
     * 사용자를 <b>깨울 만한가</b>.
     *
     * <p>두 관문이 있고 뜻이 다르다. 개선폭은 "그만큼 빨라지는가"이고 리드타임은 "지금 알려도
     * 내릴 수 있는가"다. 후자는 목록을 만든 뒤 시간이 흐르는 동안 다시 무너질 수 있어 조회
     * 경로가 한 번 더 본다({@code RerouteProposalService.validate}).
     */
    private boolean worthWaking(List<RerouteCandidate> candidates, Instant now) {
        if (gainOf(candidates) < properties.getAlert().getMinimumGainSeconds()) {
            metrics.recordAlert("below-threshold");
            return false;
        }
        Instant earliest = now.plusSeconds(properties.getNextStop().getLeadTimeSec());
        if (candidates.stream().noneMatch(candidate -> hasLeadTime(candidate, earliest))) {
            metrics.recordAlert("no-lead-time");
            return false;
        }
        return true;
    }

    /**
     * 목록의 <b>최선</b> 개선폭. 후보는 이미 순서대로 오지만 그 전제에 기대지 않는다 — 정렬
     * 규칙이 바뀌는 날 알림 문턱이 조용히 함께 바뀐다.
     */
    private int gainOf(List<RerouteCandidate> candidates) {
        return candidates.stream()
                .mapToInt(candidate -> candidate.display().etaGainSec())
                .max()
                .orElse(0);
    }

    /**
     * 이 후보의 하차점이 아직 <b>남아 있는가</b>.
     *
     * <p>하차 정보가 없는 후보(③)는 통과시킨다 — leg 경계에서 갈아타는 옛 모양이라 "정류장을
     * 지나쳤다"가 성립하지 않는다. 원점 창(PR-2)이 이미 리드타임을 보장하므로 이 확인은 그
     * 사이에 흐른 시간을 보는 것이다.
     */
    private boolean hasLeadTime(RerouteCandidate candidate, Instant earliest) {
        RerouteCandidateDisplay.AlightStop alight = candidate.display().alight();
        return alight == null || alight.alightAt() == null || !alight.alightAt().isBefore(earliest);
    }
}
