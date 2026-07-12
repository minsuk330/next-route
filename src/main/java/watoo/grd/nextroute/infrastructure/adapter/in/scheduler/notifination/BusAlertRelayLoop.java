package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.notifination;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.service.BusAlertOutboxRelayService;
import watoo.grd.nextroute.application.notification.service.RelayWaker;

import java.time.Clock;
import java.time.LocalTime;

/**
 * outbox relay 루프 스레드. enqueue afterCommit wake 신호를 받으면 즉시 발송(빌 때까지 drain),
 * 신호가 없으면 sweep 주기(await timeout)에 깨어 재스캔(=fallback sweeper). 기존 @Scheduled relay 대체.
 *
 * <p>지연 하한이 스케줄 간격에 묶이지 않고 event wake latency(ms) 로 내려간다. 정합성은 여전히 outbox
 * 소유 — 신호는 힌트일 뿐, 유실 시 timeout sweep 이 복구한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusAlertRelayLoop implements SmartLifecycle {

    private final BusAlertOutboxRelayService relayService;
    private final RelayWaker relayWaker;
    private final BusArrivalAlertProperties properties;
    private final Clock clock;

    private volatile boolean running = false;
    private Thread thread;

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "bus-relay-loop");
        thread.setDaemon(true);
        thread.start();
        log.info("[BusAlertRelayLoop] started (sweep={}ms)", properties.getOutboxSweepIntervalMs());
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        log.info("[BusAlertRelayLoop] stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            // 신호 or sweep timeout 까지 대기. 둘 다 drain 트리거.
            relayWaker.await(properties.getOutboxSweepIntervalMs());
            if (!running) break;
            if (!properties.isEnabled() || !isActiveHours()) continue;
            try {
                relayService.drainUntilEmpty();
            } catch (Exception e) {
                log.error("[BusAlertRelayLoop] drain 실패: {}", e.getMessage(), e);
            }
        }
    }

    /** active window는 자정을 넘을 수 있다(start > end). BusArrivalAlertScheduler 와 동일 규칙. */
    private boolean isActiveHours() {
        LocalTime now = LocalTime.now(clock);
        LocalTime start = LocalTime.parse(properties.getActiveHours().getStart());
        LocalTime end = LocalTime.parse(properties.getActiveHours().getEnd());
        if (start.equals(end)) return true;
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        boolean inactive = !now.isBefore(end) && now.isBefore(start);
        return !inactive;
    }
}
