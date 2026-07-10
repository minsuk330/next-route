package watoo.grd.nextroute.application.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.domain.notification.entity.AlertStatus;
import watoo.grd.nextroute.domain.notification.entity.BusAlertNotification;
import watoo.grd.nextroute.domain.notification.entity.BusArrivalAlert;
import watoo.grd.nextroute.domain.notification.entity.BusNotificationStatus;
import watoo.grd.nextroute.domain.notification.repository.BusAlertNotificationRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * outbox 발송 상태 전이의 트랜잭션 경계. claimBatch(커밋) → loadTask → 발송(tx 밖) → mark 구조.
 * markSent 는 outbox SENT + 구독 SENT 를 원자로 처리하되, 발송 중 취소된 구독을 되살리지 않는다(재검증).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusAlertOutboxStateService {

    private final BusAlertNotificationRepository notificationRepository;
    private final BusArrivalAlertProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 발송 작업 지시. context 는 토스 템플릿 파라미터(payload 스냅샷). */
    public record RelayTask(long notificationId, String channel, long tossUserKey, Map<String, Object> context) {
    }

    /** PROCESSING 고착 reclaim. */
    @Transactional
    public void reclaim(LocalDateTime now) {
        notificationRepository.reclaimStale(now.minusMinutes(properties.getClaimTimeoutMinutes()), now);
    }

    /** 배치 claim(cancelStale 없음 — 버스는 부모 PROCESSING EXISTS 로 이미 걸러짐). id 목록 반환. */
    @Transactional
    public List<Long> claimBatch(LocalDateTime now, int batchSize) {
        return notificationRepository.claimBatchForProcessing(now, batchSize);
    }

    /** claim 된 outbox 를 재확인하고 발송 컨텍스트 구성. 부모 비활성이면 CANCELED 후 skip. */
    @Transactional
    public Optional<RelayTask> loadTask(long notificationId) {
        BusAlertNotification n = notificationRepository.findById(notificationId).orElse(null);
        if (n == null || n.getStatus() != BusNotificationStatus.PROCESSING) return Optional.empty();

        BusArrivalAlert alert = n.getAlert();
        LocalDateTime now = LocalDateTime.now(clock);
        if (isInactive(alert) || alert.getExpiresAt().isBefore(now)) {
            n.markCanceled();
            return Optional.empty();
        }
        return Optional.of(new RelayTask(
                n.getId(), n.getChannel(), alert.getUser().getTossUserKey(), buildContext(n)));
    }

    private Map<String, Object> buildContext(BusAlertNotification n) {
        Map<String, Object> ctx = new HashMap<>();
        if (n.getPayload() == null) return ctx;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> snap = objectMapper.readValue(n.getPayload(), Map.class);
            ctx.putAll(snap);
        } catch (Exception e) {
            log.warn("[BusAlertOutbox] payload 파싱 실패 id={}: {}", n.getId(), e.getMessage());
        }
        return ctx;
    }

    /**
     * 발송 성공: outbox SENT + (구독이 여전히 PROCESSING 이면) 구독 SENT 원자.
     * 발송 중 사용자가 취소(구독 CANCELED)했으면 구독을 되살리지 않고 outbox 만 SENT 기록.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(long notificationId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            LocalDateTime now = LocalDateTime.now(clock);
            n.markSent(now);
            BusArrivalAlert alert = n.getAlert();
            if (alert.getStatus() == AlertStatus.PROCESSING && alert.getDeletedAt() == null) {
                alert.markSent(now); // 마지막(현재 유일) 채널 → 구독 종결
            }
        });
    }

    /** transient 실패: 재시도 예산 남으면 백오프 PENDING, 소진되면 DEAD. */
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

    /** permanent 실패: outbox FAILED + 구독 FAILED(발송 불가 종결). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPermanentFailure(long notificationId, String reason) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            n.markFailed(reason);
            BusArrivalAlert alert = n.getAlert();
            if (alert.getStatus() == AlertStatus.PROCESSING && alert.getDeletedAt() == null) {
                alert.markFailed(reason);
            }
        });
    }

    private boolean isInactive(BusArrivalAlert alert) {
        return alert.getStatus() != AlertStatus.PROCESSING || alert.getDeletedAt() != null;
    }
}
