package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 평가 트랜잭션이 커밋된 뒤에 여정 relay 를 깨운다.
 *
 * <p>{@code AFTER_COMMIT} 이라 깨우는 시점에는 outbox 행이 이미 보인다. 커밋 전에 깨우면 relay 가
 * 아직 없는 행을 찾아 헛돈다.
 *
 * <p>롤백되면 아예 발행되지 않는다 — 트랜잭션에 묶인 이벤트이기 때문이다.
 *
 * <p>깨우기는 힌트일 뿐이라 여기서 나는 예외는 삼킨다. 알림을 못 깨운 것보다 평가가 실패하는
 * 것이 나쁘고, 유실은 루프의 sweep 주기가 복구한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JourneyRelayWakeListener {

    private final JourneyRelayWaker relayWaker;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEnqueued(JourneyOutboxEnqueuedEvent event) {
        try {
            relayWaker.wake();
        } catch (Exception e) {
            log.warn("[JourneyRelayWake] wake 실패: {}", e.getMessage());
        }
    }
}
