package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.notification.repository.BusArrivalAlertRepository;

import java.time.LocalDateTime;

/**
 * 구독 만료 경계. 발송 상태 전이(claim/mark)는 outbox 경로가 소유하므로
 * ({@link BusAlertOutboxEnqueueService}/{@link BusAlertOutboxStateService}) 여기서는 만료만 다룬다.
 * <p>구독 reclaim 은 하지 않는다 — 인계된 PROCESSING 구독을 PENDING 으로 되돌리면 재enqueue 시
 * dedup unique 충돌(무한 루프). 재시도 소유권은 outbox relay 가 단독으로 갖는다.
 */
@Service
@RequiredArgsConstructor
public class BusAlertStateService {

    private final BusArrivalAlertRepository alertRepository;

    /** PENDING 만료(bulk). 발송 인계된 PROCESSING 은 건드리지 않는다. */
    @Transactional
    public void expireAndReclaim(LocalDateTime now) {
        alertRepository.expirePendingOverdue(now);
    }
}
