package watoo.grd.nextroute.application.notification.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.config.TossMessengerProperties;
import watoo.grd.nextroute.application.notification.exception.TossMessengerException;
import watoo.grd.nextroute.application.notification.port.out.TossMessengerPort;
import watoo.grd.nextroute.application.notification.service.BusAlertOutboxStateService.RelayTask;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * outbox relay. reclaim → 배치 claim(커밋) → loadTask → 토스 발송(tx 밖) → mark.
 * polling 과 분리돼 외부 토스 지연이 발송 조건 판단 루프에 영향을 주지 않는다.
 * <p>Step C: claim 은 이미 원자(SKIP LOCKED)이므로, claim 된 배치의 느린 Toss 발송만 bounded 워커 풀로
 * 팬아웃한다. 각 outbox 행은 이미 커밋된 PROCESSING(서로 disjoint)이라 워커 간 정합성 충돌이 없다.
 * outboxRelayWorkers ≤ 1 이면 인라인 처리(Step B 와 동치).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusAlertOutboxRelayService {

    private final BusAlertOutboxStateService stateService;
    private final TossMessengerPort tossMessengerPort;
    private final BusArrivalAlertProperties properties;
    private final TossMessengerProperties messengerProperties;
    private final Clock clock;

    /** 발송 팬아웃 풀. lazy 생성(첫 relay 시 properties 반영), 워커 수는 부팅 후 고정. */
    private volatile ExecutorService sendPool;

    public void relayOnce() {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            stateService.reclaim(now);

            List<Long> claimed = stateService.claimBatch(now, properties.getOutboxRelayBatchSize());
            if (claimed.isEmpty()) return;

            int workers = Math.max(1, properties.getOutboxRelayWorkers());
            if (workers <= 1 || claimed.size() == 1) {
                claimed.forEach(this::process);
            } else {
                drainParallel(claimed, workers);
            }
        } catch (Exception e) {
            log.error("[BusAlertOutboxRelay] relay 실패: {}", e.getMessage(), e);
        }
    }

    /** claim 된 배치를 워커 풀로 팬아웃 발송하고 전량 완료까지 대기. */
    private void drainParallel(List<Long> claimed, int workers) {
        ExecutorService pool = pool(workers);
        List<Future<?>> futures = new java.util.ArrayList<>(claimed.size());
        for (Long id : claimed) {
            futures.add(pool.submit(() -> process(id)));
        }
        for (Future<?> f : futures) {
            try {
                // 발송은 개별 워커 내부에서 이미 예외를 흡수(process). 여기선 완료만 보장.
                f.get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[BusAlertOutboxRelay] 워커 대기 실패: {}", e.getMessage());
            }
        }
    }

    /** 단일 outbox 처리: loadTask(재검증) → 발송. 개별 실패는 삼켜 다른 건 진행. */
    private void process(Long id) {
        try {
            Optional<RelayTask> task = stateService.loadTask(id);
            task.ifPresent(this::send);
        } catch (Exception e) {
            log.warn("[BusAlertOutboxRelay] notification={} 처리 실패: {}", id, e.getMessage());
        }
    }

    private void send(RelayTask task) {
        // 현재 채널은 TOSS 만. 채널 확장 시 task.channel() 로 분기.
        String templateSetCode = messengerProperties.getBusArrivalTemplateSetCode();
        try {
            tossMessengerPort.sendMessage(task.tossUserKey(), templateSetCode, task.context());
            stateService.markSent(task.notificationId());
        } catch (TossMessengerException e) {
            if (e.isPermanent()) {
                log.warn("[BusAlertOutboxRelay] notification={} permanent 실패: {}", task.notificationId(), e.getMessage());
                stateService.markPermanentFailure(task.notificationId(), e.getMessage());
            } else {
                stateService.markTransientFailure(task.notificationId(), e.getMessage());
            }
        }
    }

    private ExecutorService pool(int workers) {
        ExecutorService p = sendPool;
        if (p == null) {
            synchronized (this) {
                p = sendPool;
                if (p == null) {
                    p = Executors.newFixedThreadPool(workers, namedFactory());
                    sendPool = p;
                }
            }
        }
        return p;
    }

    private ThreadFactory namedFactory() {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "bus-relay-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @PreDestroy
    void shutdown() {
        ExecutorService p = sendPool;
        if (p != null) p.shutdownNow();
    }
}
