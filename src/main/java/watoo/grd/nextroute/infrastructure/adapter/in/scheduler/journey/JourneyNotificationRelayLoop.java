package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties.RelayMode;
import watoo.grd.nextroute.application.journey.service.JourneyNotificationLeaseKeeper;
import watoo.grd.nextroute.application.journey.service.JourneyNotificationRelayService;
import watoo.grd.nextroute.application.journey.service.JourneyRelayWaker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 여정 알림 relay 루프 스레드.
 *
 * <p>enqueue 커밋 신호를 받으면 즉시 비우고, 신호가 없으면 sweep 주기에 깨어 다시 훑는다. 신호는
 * 힌트일 뿐이라 유실돼도 sweep 이 복구한다 — 정합성은 outbox 표가 소유한다.
 *
 * <p>모드(#451)에 따라 스레드 모양이 다르다.
 * <ul>
 *   <li>BATCH — 루프 스레드 하나가 {@code drainUntilEmpty} 를 돈다(기존)</li>
 *   <li>PULL — 워커 N개가 각자 {@code pullOnce} 를 돈다. 빈 claim 이면 세대가 바뀌거나 sweep 까지 쉰다</li>
 * </ul>
 * 두 모드 모두 heartbeat 스레드 하나가 진행 중 행을 살아 있다고 알리고, 끊긴 행을 회수한다.
 *
 * <p>종료 순서: 워커 중단(새 claim 금지) → 진행 중 기록 마무리(최대 회수 기준/2) → heartbeat 중단. heartbeat 를 먼저 끄면
 * 마무리 중인 행이 회수돼 두 번 나갈 수 있다.
 *
 * <p>{@code worker} 프로필 <b>이면서</b> 설정이 켜져 있을 때만 빈이 뜬다.
 */
@Slf4j
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "journey.notification.relay", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class JourneyNotificationRelayLoop implements SmartLifecycle {

    /** claim·heartbeat 예외 뒤 쉬는 시간. 바쁜 헛돌기로 DB 를 두드리지 않는다. */
    static final long ERROR_PAUSE_MS = 1_000;

    private final JourneyNotificationRelayService relayService;
    private final JourneyRelayWaker relayWaker;
    private final JourneyNotificationProperties properties;
    private final JourneyNotificationLeaseKeeper leaseKeeper;
    private final Environment environment;

    private volatile boolean running = false;
    private Thread thread;
    private final List<Thread> pullWorkers = new ArrayList<>();
    private ScheduledExecutorService heartbeat;

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        RelayMode mode = properties.getRelay().getMode();
        if (mode == RelayMode.PULL) {
            warnIfPoolTooSmall();
            int workers = properties.getRelay().getPullWorkers();
            for (int i = 1; i <= workers; i++) {
                Thread worker = new Thread(this::pullLoop, "journey-relay-pull-" + i);
                worker.setDaemon(true);
                pullWorkers.add(worker);
                worker.start();
            }
        } else {
            thread = new Thread(this::loop, "journey-relay-loop");
            thread.setDaemon(true);
            thread.start();
        }
        long interval = properties.getRelay().getHeartbeatIntervalMs();
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "journey-relay-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleWithFixedDelay(this::beat, interval, interval, TimeUnit.MILLISECONDS);
        log.info("[JourneyNotificationRelayLoop] started (mode={}, sweep={}ms, heartbeat={}ms, claimTimeout={}s)",
                mode, properties.getRelay().getSweepIntervalMs(), interval,
                properties.claimTimeout().toSeconds());
    }

    @Override
    public synchronized void stop() {
        running = false;
        long deadline = System.nanoTime() + properties.claimTimeout().dividedBy(2).toNanos();
        if (thread != null) {
            thread.interrupt();
            join(thread, TimeUnit.SECONDS.toNanos(5));
            thread = null;
        }
        if (!pullWorkers.isEmpty()) {
            // 새 claim 은 running=false 로 막혔다. 진행 중인 발송·기록은 끝내게 두고, 시한이 지나면 인터럽트한다.
            relayWaker.wakeAll();
            for (Thread worker : pullWorkers) {
                join(worker, deadline - System.nanoTime());
            }
            for (Thread worker : pullWorkers) {
                if (worker.isAlive()) {
                    worker.interrupt();
                }
            }
            pullWorkers.clear();
        }
        if (heartbeat != null) {
            heartbeat.shutdownNow();
            heartbeat = null;
        }
        log.info("[JourneyNotificationRelayLoop] stopped");
    }

    private static void join(Thread t, long nanos) {
        try {
            t.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    /** PULL 워커 하나. 세대는 claim 전에 읽는다 — 빈 claim 과 잠들기 사이에 온 신호를 놓치지 않는다. */
    void pullLoop() {
        while (running) {
            long seen = relayWaker.generation();
            int claimed;
            try {
                claimed = relayService.pullOnce();
            } catch (Exception e) {
                log.error("[JourneyNotificationRelayLoop] pull 실패: {}", e.getMessage(), e);
                pause();
                continue;
            }
            if (claimed == 0 && running) {
                relayWaker.awaitChange(seen, properties.getRelay().getSweepIntervalMs());
            }
        }
    }

    private void beat() {
        if (leaseKeeper.beat() < 0) {
            pause();
        }
    }

    private void pause() {
        try {
            Thread.sleep(ERROR_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * PULL 워커는 claim·기록 때 커넥션을 하나씩 쓴다. 평가기 풀과 합쳐 Hikari 풀을 넘기면 커넥션 대기가 생긴다. 풀 크기가
     * 프로필마다 달라 실패로 막지 않고 경고만 남긴다.
     */
    private void warnIfPoolTooSmall() {
        int pool = environment.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class, 10);
        int evaluator = environment.getProperty("journey.navigation.evaluator.pool-size", Integer.class, 16);
        int budget = pool - evaluator - 2;
        int workers = properties.getRelay().getPullWorkers();
        if (workers > budget) {
            log.warn("[JourneyNotificationRelayLoop] pull-workers={} 가 커넥션 여유(hikari {} − 평가기 {} − 2 = {})를 넘는다. "
                    + "동시에 claim·기록하면 커넥션 대기가 생긴다(hikaricp.connections.acquire 를 본다)",
                    workers, pool, evaluator, budget);
        }
    }
}
