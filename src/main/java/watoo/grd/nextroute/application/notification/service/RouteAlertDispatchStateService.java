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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 발송 상태 전이의 트랜잭션 경계. 배치 claim(SKIP LOCKED)과 발송성공 전이를 분리해
 * 외부 토스 호출이 DB 트랜잭션 밖에서 일어나게 한다. DEPARTURE 발송성공은 부모 alert 전이(recordNotified)와
 * 같은 트랜잭션에서 처리한다(원자성).
 * <p>흐름: reclaim → claimBatch(cancelStale+SKIP LOCKED, 커밋) → per-id loadTask → 토스 발송(tx 밖) → markXxx.
 * claim 트랜잭션을 먼저 커밋하고 mark 를 별도 트랜잭션으로 두어, claim 이 연 커넥션과 mark 커넥션이 겹치지 않게 한다.
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

    /** admin requeue 결과: 실제 재투입 건과 (부모 비활성/상한 초과 등으로) 건너뛴 id. */
    public record RequeueResult(int requeued, List<Long> skipped) {
    }

    /** PROCESSING 고착 reclaim. SKIP LOCKED 락은 tx 커밋 시 풀리므로 crash 소유권 회수는 이 경로가 담당. */
    @Transactional
    public void reclaim(LocalDateTime now) {
        notificationRepository.reclaimStale(now.minusMinutes(properties.getClaimTimeoutMinutes()), now);
    }

    /**
     * stale 정리 + 배치 원자 claim. 단일 트랜잭션에서 cancelStale 후 SKIP LOCKED 로 PENDING→PROCESSING 전이,
     * claim 된 notification id 목록을 반환한다. 엔티티는 로드하지 않는다(id 만).
     */
    @Transactional
    public List<Long> claimBatch(LocalDateTime now, int batchSize) {
        notificationRepository.cancelStaleDispatchable(now);
        return notificationRepository.claimBatchForProcessing(now, batchSize);
    }

    /**
     * claim 된 notification(PROCESSING)을 재확인하고 발송 컨텍스트를 구성한다.
     * claim 커밋 이후 부모 alert 가 비활성화(수정으로 SUPERSEDED/조기 DONE·soft-delete)됐으면 발송 안 함(CANCELED).
     */
    @Transactional
    public Optional<DispatchTask> loadTask(long notificationId) {
        RouteArrivalAlertNotification n = notificationRepository.findById(notificationId).orElse(null);
        if (n == null || n.getStatus() != RouteNotificationStatus.PROCESSING) return Optional.empty();

        RouteArrivalAlert alert = n.getAlert();
        LocalDateTime now = LocalDateTime.now(clock);

        // claim~loadTask 사이에 부모가 전이됐을 수 있으므로 stale 가드 재확인(이중 방어).
        if (isInactive(alert) || alert.getTargetArrivalAt().isBefore(now)
                || (n.getType() == RouteNotificationType.DEPARTURE
                    && alert.getPhase() != RouteAlertPhase.WAITING_DEPARTURE)) {
            n.markCanceled();
            return Optional.empty();
        }

        return Optional.of(new DispatchTask(
                n.getId(), n.getType(), alert.getUser().getTossUserKey(), buildContext(alert, n)));
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
        RouteArrivalAlert alert = n.getAlert();
        // claim 후 발송 사이에 스케줄 수정으로 alert 가 대체(SUPERSEDED/조기 DONE·soft-delete)됐으면
        // recordNotified 로 phase 를 되살리지 말고 notification 만 CANCELED(PROCESSING 방치 시 reclaimStale 재시도 방지).
        if (isInactive(alert)) {
            n.markCanceled();
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        n.markSent(now);
        LocalDateTime finalCheckAt = computeFinalCheckAt(alert, now);
        alert.recordNotified(alert.getRecommendedLeaveByAt(), now, finalCheckAt);
    }

    /** CHANGE 발송성공: notification SENT 만(alert 는 이미 final check 로 DONE). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markChangeSent(long notificationId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            if (isInactive(n.getAlert())) {
                n.markCanceled();
                return;
            }
            n.markSent(LocalDateTime.now(clock));
        });
    }

    /** 스케줄 수정으로 대체된(비-ACTIVE 또는 soft-delete) alert — 발송 기록 금지. */
    private boolean isInactive(RouteArrivalAlert alert) {
        return alert.getStatus() != RouteAlertStatus.ACTIVE || alert.getDeletedAt() != null;
    }

    /** transient 실패: 재시도 예산 남으면 백오프 후 PENDING, 소진되면 DEAD(수동 개입 대기). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markTransientFailure(long notificationId, String reason) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            LocalDateTime now = LocalDateTime.now(clock);
            if (n.getAttemptCount() >= properties.getMaxAttempts()) {
                n.markDead("max-retry: " + reason);
            } else {
                n.releaseToPending(reason, now.plusSeconds(properties.getBackoffSeconds()));
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPermanentFailure(long notificationId, String reason) {
        notificationRepository.findById(notificationId).ifPresent(n -> n.markFailed(reason));
    }

    /** admin: DEAD row 목록(최신순). */
    @Transactional(readOnly = true)
    public List<RouteArrivalAlertNotification> findDead() {
        return notificationRepository.findByStatusOrderByCreatedAtDesc(RouteNotificationStatus.DEAD);
    }

    /**
     * admin: DEAD → PENDING 재투입. 부모가 ACTIVE·미래 target 이고 requeueCount 상한 미만인 것만.
     * resetAttempts=true 면 재시도 예산 전체 재부여(기본은 1회만 더).
     */
    @Transactional
    public RequeueResult requeueDead(List<Long> ids, boolean resetAttempts) {
        LocalDateTime now = LocalDateTime.now(clock);
        int requeued = 0;
        List<Long> skipped = new ArrayList<>();
        for (Long id : ids) {
            RouteArrivalAlertNotification n = notificationRepository.findById(id).orElse(null);
            if (n == null || n.getStatus() != RouteNotificationStatus.DEAD
                    || n.getRequeueCount() >= properties.getMaxRequeue()
                    || isInactive(n.getAlert()) || !n.getAlert().getTargetArrivalAt().isAfter(now)) {
                skipped.add(id);
                continue;
            }
            n.requeue(now, resetAttempts);
            requeued++;
        }
        return new RequeueResult(requeued, skipped);
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
