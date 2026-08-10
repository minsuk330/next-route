package watoo.grd.nextroute.application.journey.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 여정 relay 를 즉시 깨우는 신호. enqueue 커밋 뒤에 {@link #wake()}, relay 루프가 {@link #await}.
 *
 * <p>버스 쪽 {@code RelayWaker} 를 <b>복제</b>했다. 공유하지 않는 이유는 신호가 섞이기 때문이다 —
 * 여정은 초당 수백 건을 쌓을 수 있고, 그 신호가 버스 relay 루프까지 그만큼 깨운다. 상속으로
 * 풀지도 않는다. 같은 타입의 빈이 둘이 되어 주입이 모호해진다.
 *
 * <p>신호는 <b>힌트일 뿐</b>이다. 유실돼도 정합성은 outbox 표가 소유하고, 루프의 sweep 주기가
 * 복구한다. permit 을 최대 하나로 합쳐(coalescing) 몰아치는 enqueue 가 drain 을 여러 번 돌리지
 * 않게 한다. drain 중에 온 신호는 permit 으로 남아 다음 회전이 즉시 다시 돈다.
 */
@Component
public class JourneyRelayWaker {

    private final Semaphore signal = new Semaphore(0);

    /** 신호 발행(합침). 이미 대기 중인 permit 이 있으면 더하지 않는다. */
    public synchronized void wake() {
        if (signal.availablePermits() == 0) {
            signal.release();
        }
    }

    /**
     * 새 알림이 들어올때까지 대기, 신호 또는 timeout으로 깨어나는 함수다.
     *
     * @return 신호를 받았으면 true, 시간 초과(= sweep 시점)면 false
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
