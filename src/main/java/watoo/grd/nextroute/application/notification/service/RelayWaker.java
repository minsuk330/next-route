package watoo.grd.nextroute.application.notification.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * relay 즉시 wake-up 신호. outbox enqueue afterCommit 에 {@link #wake()}, relay 루프가 {@link #await}.
 *
 * <p>signal 은 "빨리 깨우는 힌트"일 뿐이다 — 유실돼도 정합성은 outbox 가 소유하고 fallback sweep(await
 * timeout)이 복구한다. permit 을 최대 1개로 병합(coalescing)해 burst enqueue 가 drain 을 여러 번 돌리지
 * 않게 한다. drain 중 새 wake 는 permit 으로 남아 다음 loop 가 즉시 재-drain(신호 유실 없음).
 */
@Component
public class RelayWaker {

    private final Semaphore signal = new Semaphore(0);

    /** 신호 발행(병합). 이미 대기 중인 permit 이 있으면 추가하지 않는다. */
    public synchronized void wake() {
        if (signal.availablePermits() == 0) {
            signal.release();
        }
    }

    /**
     * 신호 또는 timeout 까지 대기. 신호 수신=true, timeout(=sweep 시점)=false.
     * interrupt 시 interrupt 플래그 복원 후 false 반환(루프 종료 유도).
     */
    public boolean await(long timeoutMs) {
        try {
            return signal.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
