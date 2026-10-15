package watoo.grd.nextroute.application.journey.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.ClaimedNotification;
import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.exception.NotificationSendException;
import watoo.grd.nextroute.application.journey.exception.NotificationSkippedException;
import watoo.grd.nextroute.application.journey.exception.StaleClaimException;
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;
import watoo.grd.nextroute.application.journey.port.out.NotificationSender;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * outbox 를 비우는 relay. claim(커밋) → 발송 직전 재확인 → 발송(트랜잭션 밖) → 증서 조건부 기록.
 *
 * <p>두 방식이 있다(#451, {@code journey.notification.relay.mode}).
 * <ul>
 *   <li>BATCH({@link #drainUntilEmpty}) — 배치 하나를 잡아 고정 풀로 보내고 전부 기다린다(기존)</li>
 *   <li>PULL({@link #pullOnce}) — 워커마다 k건씩 당겨 보낸다. 워커 스레드는 relay 루프가 만든다</li>
 * </ul>
 * 회수는 여기서 하지 않는다. heartbeat 스레드({@link JourneyNotificationLeaseKeeper#beat})가 heartbeat 뒤에 한다.
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
    private final JourneyNotificationLeaseKeeper leaseKeeper;
    private final JourneyRelayWaker relayWaker;
    private final JourneyNotificationMetrics metrics;
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
            List<ClaimedNotification> claimed = claim(properties.getRelay().getBatchSize());
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

    /**
     * PULL 한 번: k건을 집어 차례로 보낸다. 꽉 차게(k건) 집었으면 아직 남았을 수 있으니 쉬는 워커 하나를 더 깨운다.
     *
     * <p>claim 예외는 그대로 던진다 — 워커 루프가 1초 쉬고 다시 돈다(바쁜 헛돌기 금지).
     *
     * @return 집은 건수. 0 이면 워커는 깨우기나 sweep 까지 쉰다
     */
    public int pullOnce() {
        int claimSize = Math.max(1, properties.getRelay().getPullClaimSize());
        List<ClaimedNotification> claimed = claim(claimSize);
        if (claimed.size() >= claimSize) {
            relayWaker.wakeNext();
        }
        claimed.forEach(this::process);
        return claimed.size();
    }

    /** claim 하고 곧바로 heartbeat 대상에 올린다. */
    private List<ClaimedNotification> claim(int size) {
        List<ClaimedNotification> claimed = stateService.claimBatch(Instant.now(clock), size);
        claimed.forEach(leaseKeeper::hold);
        metrics.recordClaimSize(claimed.size());
        return claimed;
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** claim 한 배치를 워커 풀로 나눠 보내고 전부 끝날 때까지 기다린다. */
    private void drainParallel(List<ClaimedNotification> claimed, int workers) {
        ExecutorService pool = pool(workers);
        List<Future<?>> futures = new ArrayList<>(claimed.size());
        for (ClaimedNotification claim : claimed) {
            futures.add(pool.submit(() -> process(claim)));
        }
        for (Future<?> future : futures) {
            try {
                future.get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[JourneyNotificationRelay] 워커 대기 실패: {}", e.getMessage());
            }
        }
    }

    /**
     * 한 건 처리. 개별 실패는 삼켜 다른 건이 계속 나가게 한다.
     *
     * <p>늦은 기록({@link StaleClaimException})은 이 행이 이미 남의 것이라는 뜻이다. 상태·울릴 권리는 그 트랜잭션이 되돌렸고,
     * 여기서는 세기만 한다. 어느 쪽이든 끝나면 heartbeat 대상에서 뺀다.
     */
    private void process(ClaimedNotification claim) {
        try {
            stateService.loadTask(claim).ifPresent(command -> send(claim, command));
        } catch (StaleClaimException e) {
            metrics.recordStaleWrite(e.getOp());
            log.info("[JourneyNotificationRelay] id={} 늦은 기록 버림({})", claim.id(), e.getOp());
        } catch (Exception e) {
            log.warn("[JourneyNotificationRelay] id={} 처리 실패: {}", claim.id(), e.getMessage());
        } finally {
            leaseKeeper.release(claim);
        }
    }

    /**
     * 보내고 결과를 기록한다. 발송 결과를 먼저 정하고 기록은 그 뒤에 한 번만 한다 — 접수 기록이 실패했다고 일시 실패로
     * 다시 기록하면 늦은 기록이 두 번 된다.
     */
    private void send(ClaimedNotification claim, NotificationSendCommand command) {
        try {
            sender.send(command);
        } catch (NotificationSkippedException e) {
            stateService.markSkipped(claim, e.getReason());
            return;
        } catch (NotificationSendException e) {
            if (e.isPermanent()) {
                log.warn("[JourneyNotificationRelay] id={} 영구 실패: {}",
                        command.notificationId(), e.getMessage());
                stateService.markPermanentFailure(claim, e.getMessage());
            } else {
                stateService.markTransientFailure(claim, e.getMessage());
            }
            return;
        } catch (Throwable t) {
            // 예상 못 한 예외(NPE·SDK 내부 오류·타임아웃)를 안 잡으면 행이 PROCESSING 에 남아
            // 회수 → 재시도 → 또 예외를 영원히 반복한다. 일시 실패로 보고 재시도 예산을 태운다.
            log.error("[JourneyNotificationRelay] id={} 예상 못 한 발송 오류",
                    command.notificationId(), t);
            stateService.markTransientFailure(claim, "unexpected: " + t);
            return;
        }
        stateService.markSent(claim);
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
