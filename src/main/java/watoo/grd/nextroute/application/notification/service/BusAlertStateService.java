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

    /**
     * PENDING 만료 + 고착 PROCESSING reclaim (bulk).
     * outbox on 이면 구독 reclaim 을 끈다 — 인계된 PROCESSING 구독을 PENDING 으로 되돌리면
     * 재enqueue 시 dedup unique 충돌 → 무한 루프. 재시도 소유권은 outbox relay 가 단독으로 갖는다.
     */
    @Transactional
    public void expireAndReclaim(LocalDateTime now) {
        alertRepository.expirePendingOverdue(now);
        if (!properties.isOutboxEnabled()) {
            alertRepository.reclaimStale(now.minusMinutes(properties.getClaimTimeoutMinutes()));
        }
    }

    /**
     * PENDING → PROCESSING 원자 claim. 영향행수 1=승자, 0=이미 비-PENDING/경쟁서 짐.
     * 조건부 UPDATE 라 read-then-write race(동시 worker 중복 발송) 가 없다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long alertId) {
        return alertRepository.claimForProcessing(alertId, LocalDateTime.now(clock)) == 1;
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
