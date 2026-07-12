package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * enqueue tx 커밋 후 relay 를 즉시 깨운다. AFTER_COMMIT 이라 wake 시점엔 outbox row 가 이미 보임.
 * wake 는 힌트일 뿐이라 리스너 예외가 발송/커밋에 영향 주지 않도록 삼킨다(유실은 sweep 이 복구).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RelayWakeListener {

    private final RelayWaker relayWaker;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEnqueued(OutboxEnqueuedEvent event) {
        try {
            relayWaker.wake();
        } catch (Exception e) {
            log.warn("[RelayWake] wake 실패 notification={}: {}", event.notificationId(), e.getMessage());
        }
    }
}
