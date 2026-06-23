package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.domain.notification.entity.AlertStatus;
import watoo.grd.nextroute.domain.notification.entity.BusArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.BusArrivalAlertRepository;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 알림 상태 전이의 트랜잭션 경계. claim/markSent/markFailed 를 각각 독립 트랜잭션(REQUIRES_NEW)으로
 * 분리해 외부 토스 호출이 DB 트랜잭션 밖에서 일어나도록 한다.
 */
@Service
@RequiredArgsConstructor
public class BusAlertStateService {

    private final BusArrivalAlertRepository alertRepository;
    private final BusArrivalAlertProperties properties;
    private final Clock clock;

    /** PENDING만 대상 만료 + 고착 PROCESSING reclaim (bulk). */
    @Transactional
    public void expireAndReclaim(LocalDateTime now) {
        alertRepository.expirePendingOverdue(now);
        alertRepository.reclaimStale(now.minusMinutes(properties.getClaimTimeoutMinutes()));
    }

    /** PENDING → PROCESSING. 이미 PENDING 아니면 false(동시성 가드). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long alertId) {
        BusArrivalAlert alert = alertRepository.findById(alertId).orElse(null);
        if (alert == null || alert.getStatus() != AlertStatus.PENDING) {
            return false;
        }
        alert.markProcessing(LocalDateTime.now(clock));
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(Long alertId) {
        alertRepository.findById(alertId).ifPresent(a -> a.markSent(LocalDateTime.now(clock)));
    }

    /** transient 실패: maxAttempts 초과면 FAILED, 아니면 PENDING 복귀(백오프 재시도). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markTransientFailure(Long alertId, String reason) {
        alertRepository.findById(alertId).ifPresent(a -> {
            if (a.getAttemptCount() >= properties.getMaxAttempts()) {
                a.markFailed("max-retry: " + reason);
            } else {
                a.releaseToPending(reason);
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPermanentFailure(Long alertId, String reason) {
        alertRepository.findById(alertId).ifPresent(a -> a.markFailed(reason));
    }
}
