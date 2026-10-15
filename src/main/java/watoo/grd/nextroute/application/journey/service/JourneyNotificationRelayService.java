package watoo.grd.nextroute.application.journey.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.exception.NotificationSendException;
import watoo.grd.nextroute.application.journey.exception.NotificationSkippedException;
import watoo.grd.nextroute.application.journey.port.out.NotificationSender;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * outbox 를 비우는 relay. 회수 → 배치 claim(커밋) → 발송 직전 재확인 → 발송(트랜잭션 밖) → 기록.
 *
 * <p>{@code @Transactional} 을 <b>안 붙인다</b>. 트랜잭션 경계는 전부
 * {@link JourneyNotificationOutboxStateService} 가 소유한다 — 여기에 붙이면 발송이 트랜잭션 안에
 * 들어와 provider 응답을 기다리는 동안 DB 커넥션을 붙잡는다.
 *
 * <p>버스 relay 를 복제했다. 공통 추상화를 만들지 않는 이유는 그러려면 <b>살아 있는 토스 발송
 * 경로</b>를 고쳐야 하기 때문이다. 두 outbox 가 각자 굳은 뒤에 합치는 편이 낫다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JourneyNotificationRelayService {

    private final JourneyNotificationOutboxStateService stateService;
    private final NotificationSender sender;
    private final JourneyNotificationProperties properties;
    private final Clock clock;

    /** 발송 팬아웃 풀. 처음 쓸 때 만들고 워커 수는 그 뒤로 고정이다. */
    private volatile ExecutorService sendPool;

    /**
     * 빌 때까지 반복 처리한다.
     *
     * <p>한 배치만 처리하면 나머지가 다음 sweep 까지 밀린다. 반대로 무한정 돌면 신호를 못 받으므로
     * 배치 수·시간 상한과 인터럽트로 빠져나온다.
     */
    public void drainUntilEmpty() {
        long startNanos = System.nanoTime();
        int batches = 0;
        while (!Thread.currentThread().isInterrupted()
                && batches < properties.getRelay().getMaxBatches()
                && elapsedMillis(startNanos) < properties.getRelay().getDrainMaxMillis()) {
            if (!relayOnce()) {
                break;
            }
            batches++;
        }
    }

    /** @return 처리한 것이 있으면 true, claim 이 비었으면 false */
    public boolean relayOnce() {
        try {
            Instant now = Instant.now(clock);
            stateService.reclaim(now);

            List<Long> claimed = stateService.claimBatch(now, properties.getRelay().getBatchSize());
            if (claimed.isEmpty()) {
                return false;
            }

            int workers = Math.max(1, properties.getRelay().getWorkers());
            if (workers <= 1 || claimed.size() == 1) {
                claimed.forEach(this::process);
            } else {
                drainParallel(claimed, workers);
            }
            return true;
        } catch (Exception e) {
            log.error("[JourneyNotificationRelay] relay 실패: {}", e.getMessage(), e);
            return false;
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** claim 한 배치를 워커 풀로 나눠 보내고 전부 끝날 때까지 기다린다. */
    private void drainParallel(List<Long> claimed, int workers) {
        ExecutorService pool = pool(workers);
        List<Future<?>> futures = new ArrayList<>(claimed.size());
        for (Long id : claimed) {
            futures.add(pool.submit(() -> process(id)));
        }
        for (Future<?> future : futures) {
            try {
                future.get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[JourneyNotificationRelay] 워커 대기 실패: {}", e.getMessage());
            }
        }
    }

    /** 한 건 처리. 개별 실패는 삼켜 다른 건이 계속 나가게 한다. */
    private void process(Long id) {
        try {
            Optional<NotificationSendCommand> task = stateService.loadTask(id);
            task.ifPresent(this::send);
        } catch (Exception e) {
            log.warn("[JourneyNotificationRelay] id={} 처리 실패: {}", id, e.getMessage());
        }
    }

    private void send(NotificationSendCommand command) {
        try {
            sender.send(command);
            stateService.markSent(command.notificationId());
        } catch (NotificationSkippedException e) {
            stateService.markSkipped(command.notificationId(), e.getReason());
        } catch (NotificationSendException e) {
            if (e.isPermanent()) {
                log.warn("[JourneyNotificationRelay] id={} 영구 실패: {}",
                        command.notificationId(), e.getMessage());
                stateService.markPermanentFailure(command.notificationId(), e.getMessage());
            } else {
                stateService.markTransientFailure(command.notificationId(), e.getMessage());
            }
        } catch (Throwable t) {
            // 예상 못 한 예외(NPE·SDK 내부 오류·타임아웃)를 안 잡으면 행이 PROCESSING 에 남아
            // 회수 → 재시도 → 또 예외를 영원히 반복한다. 일시 실패로 보고 재시도 예산을 태운다.
            log.error("[JourneyNotificationRelay] id={} 예상 못 한 발송 오류",
                    command.notificationId(), t);
            stateService.markTransientFailure(command.notificationId(), "unexpected: " + t);
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
            Thread t = new Thread(r, "journey-relay-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @PreDestroy
    void shutdown() {
        ExecutorService p = sendPool;
        if (p != null) {
            p.shutdownNow();
        }
    }
}
