package watoo.grd.nextroute.application.journey.load;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey.JourneyDueEvaluatorScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * 부하 실험 중 상태를 훑고 결과를 모은다.
 *
 * <p>지연은 <b>예정 시각부터 저장 완료까지</b>로 잰다. 평가가 건너뛰어지거나 소유권을 잃은 경우는
 * 완료로 세지 않는다 — 그 여정은 다음 선점에서 다시 계산되고, 그때 기록되는 지연에 기다린 시간이
 * 모두 들어간다.
 *
 * <p>스케줄러는 이 클래스가 직접 돌린다. tick 을 부르고, 배치가 끝나면 설정된 간격만큼 쉰다 —
 * 운영의 {@code fixedDelay} 와 같은 모양이다.
 */
public final class JourneyLoadProbe {

    private final List<Long> latenciesMillis = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, AtomicLong> incompleteByReason = new ConcurrentHashMap<>();
    private final AtomicLong overlappingClaims = new AtomicLong();

    public void recordCompletion(Instant dueAt, Instant completedAt) {
        if (dueAt == null) {
            return;
        }
        latenciesMillis.add(Duration.between(dueAt, completedAt).toMillis());
    }

    public void recordIncomplete(String reason) {
        incompleteByReason.computeIfAbsent(reason, key -> new AtomicLong()).incrementAndGet();
    }

    /** 앞선 선점이 아직 유효한데 같은 여정이 다시 선점됐다. 0 이어야 한다. */
    public void recordOverlappingClaim() {
        overlappingClaims.incrementAndGet();
    }

    /**
     * 스케줄러를 창 길이만큼 돌리며 1초 간격으로 상태를 샘플링한다.
     *
     * @param tickMs 배치가 끝난 뒤 다음 tick 까지 쉬는 시간. 운영 설정과 같은 값을 넘긴다
     */
    public Report observe(JourneyDueEvaluatorScheduler scheduler, Duration window,
                          LongSupplier backlog, IntSupplier activeConnections,
                          ThreadPoolTaskExecutor executor, long tickMs) throws InterruptedException {
        List<Long> backlogSamples = Collections.synchronizedList(new ArrayList<>());
        AtomicLong maxConnections = new AtomicLong();
        AtomicLong maxQueue = new AtomicLong();

        Thread sampler = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    backlogSamples.add(backlog.getAsLong());
                    maxConnections.accumulateAndGet(activeConnections.getAsInt(), Math::max);
                    maxQueue.accumulateAndGet(executor.getThreadPoolExecutor().getQueue().size(),
                            Math::max);
                    Thread.sleep(1_000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "load-sampler");
        sampler.setDaemon(true);
        sampler.start();

        Instant deadline = Instant.now().plus(window);
        try {
            while (Instant.now().isBefore(deadline)) {
                scheduler.run();
                Thread.sleep(tickMs);
            }
        } finally {
            sampler.interrupt();
            sampler.join(2_000);
        }

        return new Report(List.copyOf(latenciesMillis), List.copyOf(backlogSamples),
                readableCounts(), overlappingClaims.get(),
                maxConnections.get(), maxQueue.get());
    }

    private Map<String, Long> readableCounts() {
        Map<String, Long> counts = new java.util.TreeMap<>();
        incompleteByReason.forEach((reason, count) -> counts.put(reason, count.get()));
        return counts;
    }

    /**
     * @param latenciesMillis 예정 시각 → 저장 완료. 완료된 평가만
     * @param backlogSamples  1초 간격 적체 표본
     */
    public record Report(List<Long> latenciesMillis, List<Long> backlogSamples,
                         Map<String, Long> incompleteByReason, long overlappingClaims,
                         long maxActiveConnections, long maxQueueSize) {

        public int completed() {
            return latenciesMillis.size();
        }

        /** 백분위 지연(초). 표본이 없으면 0. */
        public double latencySeconds(int percentile) {
            if (latenciesMillis.isEmpty()) {
                return 0;
            }
            List<Long> sorted = new ArrayList<>(latenciesMillis);
            Collections.sort(sorted);
            int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))) / 1000.0;
        }

        public double maxLatencySeconds() {
            return latenciesMillis.stream().mapToLong(Long::longValue).max().orElse(0) / 1000.0;
        }

        public long firstBacklog() {
            return backlogSamples.isEmpty() ? 0 : backlogSamples.get(0);
        }

        public long lastBacklog() {
            return backlogSamples.isEmpty() ? 0 : backlogSamples.get(backlogSamples.size() - 1);
        }

        public long maxBacklog() {
            return backlogSamples.stream().mapToLong(Long::longValue).max().orElse(0);
        }

        /** 적체가 발산하지 않았는지. 끝이 시작보다 크지 않아야 한다. */
        public boolean backlogConverged() {
            return lastBacklog() <= firstBacklog();
        }
    }
}
