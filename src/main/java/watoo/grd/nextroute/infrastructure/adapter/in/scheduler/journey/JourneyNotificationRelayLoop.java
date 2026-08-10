package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.service.JourneyNotificationRelayService;
import watoo.grd.nextroute.application.journey.service.JourneyRelayWaker;

/**
 * 여정 알림 relay 루프 스레드.
 *
 * <p>enqueue 커밋 신호를 받으면 즉시 비우고, 신호가 없으면 sweep 주기에 깨어 다시 훑는다. 신호는
 * 힌트일 뿐이라 유실돼도 sweep 이 복구한다 — 정합성은 outbox 표가 소유한다.
 *
 * <p>{@code worker} 프로필 <b>이면서</b> 설정이 켜져 있을 때만 빈이 뜬다. 기본은 꺼짐이라
 * 실제 발송 구현이 붙는 PR3 전까지는 행이 쌓이기만 한다.
 */
@Slf4j
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "journey.notification.relay", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class JourneyNotificationRelayLoop implements SmartLifecycle {

    private final JourneyNotificationRelayService relayService;
    private final JourneyRelayWaker relayWaker;
    private final JourneyNotificationProperties properties;

    private volatile boolean running = false;
    private Thread thread;

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "journey-relay-loop");
        thread.setDaemon(true);
        thread.start();
        log.info("[JourneyNotificationRelayLoop] started (sweep={}ms)",
                properties.getRelay().getSweepIntervalMs());
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
        log.info("[JourneyNotificationRelayLoop] stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            relayWaker.await(properties.getRelay().getSweepIntervalMs());
            if (!running) {
                break;
            }
            try {
                relayService.drainUntilEmpty();
            } catch (Exception e) {
                log.error("[JourneyNotificationRelayLoop] drain 실패: {}", e.getMessage(), e);
            }
        }
    }
}
