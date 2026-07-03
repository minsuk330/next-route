package watoo.grd.nextroute.application.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.notification.config.RouteArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.dto.RouteAlertPathSnapshot;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.FinalCheckEligibility;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertPhase;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlertNotification;
import watoo.grd.nextroute.domain.notification.entity.RouteNotificationStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteNotificationType;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertNotificationRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 발송 상태 전이의 트랜잭션 경계. claim/stale-guard 와 발송성공 전이를 REQUIRES_NEW 로 분리해
 * 외부 토스 호출이 DB 트랜잭션 밖에서 일어나게 한다. DEPARTURE 발송성공은 부모 alert 전이(recordNotified)와
 * 같은 트랜잭션에서 처리한다(원자성).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteAlertDispatchStateService {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final RouteArrivalAlertNotificationRepository notificationRepository;
    private final RouteArrivalAlertProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 발송 작업 지시(claim 성공 시). context 는 토스 템플릿 파라미터. */
    public record DispatchTask(long notificationId, RouteNotificationType type,
                               long tossUserKey, Map<String, Object> context) {
    }

    /** PROCESSING 고착 reclaim. */
    @Transactional
    public void reclaim(LocalDateTime now) {
        notificationRepository.reclaimStale(now.minusMinutes(properties.getClaimTimeoutMinutes()), now);
    }

    /**
     * stale 가드 + PENDING→PROCESSING claim(원자). 발송 대상이면 {@link DispatchTask} 반환, 아니면 empty.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<DispatchTask> claimForDispatch(long notificationId) {
        RouteArrivalAlertNotification n = notificationRepository.findById(notificationId).orElse(null);
        if (n == null || n.getStatus() != RouteNotificationStatus.PENDING) return Optional.empty();

        RouteArrivalAlert alert = n.getAlert();
        LocalDateTime now = LocalDateTime.now(clock);

        // stale 가드
        if (alert.getStatus() == RouteAlertStatus.CANCELED || alert.getStatus() == RouteAlertStatus.EXPIRED) {
            n.markCanceled();
            return Optional.empty();
        }
        if (alert.getTargetArrivalAt().isBefore(now)) {
            n.markCanceled();
            return Optional.empty();
        }
        // DEPARTURE 는 WAITING_DEPARTURE 에서만 유효(이미 전이됐으면 stale).
        if (n.getType() == RouteNotificationType.DEPARTURE
                && alert.getPhase() != RouteAlertPhase.WAITING_DEPARTURE) {
            n.markCanceled();
            return Optional.empty();
        }

        n.markProcessing(now);
        return Optional.of(new DispatchTask(n.getId(), n.getType(),
                alert.getUser().getTossUserKey(), buildContext(alert, n)));
    }

    private Map<String, Object> buildContext(RouteArrivalAlert alert, RouteArrivalAlertNotification n) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("leaveByAt", n.getTargetLeaveByAt().format(HHMM));
        ctx.put("targetArrivalAt", alert.getTargetArrivalAt().format(HHMM));
        if (alert.getStartName() != null) ctx.put("startName", alert.getStartName());
        if (alert.getEndName() != null) ctx.put("endName", alert.getEndName());
        if (n.getPayload() != null && n.getPayload().contains("urgent")) ctx.put("urgent", true);
        return ctx;
    }

    /** DEPARTURE 발송성공: notification SENT + 부모 alert recordNotified(전이) 원자 처리. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDepartureSent(long notificationId) {
        RouteArrivalAlertNotification n = notificationRepository.findById(notificationId).orElse(null);
        if (n == null) return;
        LocalDateTime now = LocalDateTime.now(clock);
        n.markSent(now);

        RouteArrivalAlert alert = n.getAlert();
        LocalDateTime finalCheckAt = computeFinalCheckAt(alert, now);
        alert.recordNotified(alert.getRecommendedLeaveByAt(), now, finalCheckAt);
    }

    /** CHANGE 발송성공: notification SENT 만(alert 는 이미 final check 로 DONE). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markChangeSent(long notificationId) {
        notificationRepository.findById(notificationId)
                .ifPresent(n -> n.markSent(LocalDateTime.now(clock)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markTransientFailure(long notificationId, String reason) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            LocalDateTime now = LocalDateTime.now(clock);
            if (n.getAttemptCount() >= properties.getMaxAttempts()) {
                n.markFailed("max-retry: " + reason);
            } else {
                n.releaseToPending(reason, now.plusSeconds(properties.getBackoffSeconds()));
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPermanentFailure(long notificationId, String reason) {
        notificationRepository.findById(notificationId).ifPresent(n -> n.markFailed(reason));
    }

    /**
     * final check 예정시각. userLead ≤ finalCheckLead 이거나 스냅샷 eligibility ≠ ELIGIBLE 이면
     * final check 생략(null → recordNotified 가 DONE).
     */
    private LocalDateTime computeFinalCheckAt(RouteArrivalAlert alert, LocalDateTime now) {
        if (alert.getUserLeadMinutes() <= properties.getFinalCheckLeadMinutes()) return null;
        if (parseEligibility(alert.getSelectedPathSnapshot()) != FinalCheckEligibility.ELIGIBLE) return null;
        LocalDateTime at = alert.getRecommendedLeaveByAt().minusMinutes(properties.getFinalCheckLeadMinutes());
        return at.isBefore(now) ? now : at;
    }

    private FinalCheckEligibility parseEligibility(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) return null;
        try {
            return objectMapper.readValue(snapshotJson, RouteAlertPathSnapshot.class).eligibility();
        } catch (Exception e) {
            log.warn("[RouteAlertDispatch] snapshot eligibility 파싱 실패: {}", e.getMessage());
            return null;
        }
    }
}
