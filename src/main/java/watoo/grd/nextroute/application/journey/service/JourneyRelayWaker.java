package watoo.grd.nextroute.application.journey.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

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
 *
 * <p><b>PULL 워커용 세대 카운터(#451).</b> 워커가 여럿이면 permit 하나로는 모자라다. 신호마다 세대를 올리고 쉬는 워커
 * <b>하나</b>만 깨운다 — 모두 깨우면 빈 claim 으로 DB 커넥션만 다툰다. 꽉 찬 claim 을 한 워커가 다음 워커를 깨워 연쇄로
 * 늘어난다({@link #wakeNext}). 워커는 claim <b>전에</b> 세대를 읽고({@link #generation}), 빈 claim 뒤 그 세대가 그대로일
 * 때만 잔다({@link #awaitChange}) — claim 과 잠들기 사이에 온 신호를 잃지 않는다.
 */
@Component
public class JourneyRelayWaker {

    private final Semaphore signal = new Semaphore(0);

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private long generation;

    /** 신호 발행(합침). 이미 대기 중인 permit 이 있으면 더하지 않는다. PULL 워커도 하나 깨운다. */
    public synchronized void wake() {
        if (signal.availablePermits() == 0) {
            signal.release();
        }
        wakeNext();
    }

    /** 세대를 올리고 쉬는 PULL 워커 하나를 깨운다. */
    public void wakeNext() {
        lock.lock();
        try {
            generation++;
            changed.signal();
        } finally {
            lock.unlock();
        }
    }

    /** 종료용: 쉬는 PULL 워커를 모두 깨운다. */
    public void wakeAll() {
        lock.lock();
        try {
            generation++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public long generation() {
        lock.lock();
        try {
            return generation;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 세대가 {@code seen} 에서 바뀔 때까지(또는 시간 초과까지) 기다린다. 이미 바뀌었으면 바로 돌아온다.
     *
     * @return 세대가 바뀌었으면 true, 시간 초과·인터럽트면 false
     */
    public boolean awaitChange(long seen, long timeoutMs) {
        lock.lock();
        try {
            long nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (generation == seen) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = changed.awaitNanos(nanos);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
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
