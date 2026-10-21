package watoo.grd.nextroute.application.route.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties;
import watoo.grd.nextroute.application.route.dto.*;
import watoo.grd.nextroute.application.route.exception.TmapApiException;
import watoo.grd.nextroute.application.route.observability.RouteSearchContext;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.TmapPedestrianPort;
import watoo.grd.nextroute.application.route.port.out.WalkSegmentCachePort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Step 4 검증 — provider 전용 풀(bulkhead)이 포화됐을 때의 동작.
 *
 * <p>before: TMAP 보강은 공용 {@code ForkJoinPool} 에서 실행됐다. TMAP 이 느려지면 그 스레드들이
 * 공용 풀을 점유해 같은 풀을 쓰는 다른 작업까지 함께 느려진다.
 *
 * <p>after: TMAP 은 전용 bounded 풀에서만 돈다. 포화되면 대기하지 않고 거절하고, 기본 경로는 그대로 나간다.
 */
class RouteSearchBulkheadIT {

    private static final int POOL_SIZE = 2;
    private static final int QUEUE_SIZE = 2;
    /** 풀+큐 용량(4)보다 많은 요청을 동시에 넣어 포화를 만든다. */
    private static final int CONCURRENCY = 12;
    private static final long TMAP_LATENCY_MS = 1_000;

    @Test
    @DisplayName("TMAP 풀 포화: 초과분은 거절되고, 기본 경로는 전부 정상 반환된다")
    void saturatedTmapPoolRejectsWithoutBlockingBaseRoute() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RouteSearchMetrics metrics = new RouteSearchMetrics(registry);

        ThreadPoolExecutor tmapPool = new ThreadPoolExecutor(
                POOL_SIZE, POOL_SIZE, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_SIZE),
                new ThreadPoolExecutor.AbortPolicy());

        AtomicInteger tmapCalls = new AtomicInteger();
        TmapPedestrianPort slowTmap = cmd -> {
            tmapCalls.incrementAndGet();
            try {
                TimeUnit.MILLISECONDS.sleep(TMAP_LATENCY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TmapApiException(-1, "interrupted");
            }
            return walkSegment();
        };

        WalkSegmentCachePort cache = mock(WalkSegmentCachePort.class);
        when(cache.get(any())).thenReturn(Optional.empty());   // 항상 miss

        WalkSegmentEnricher enricher =
                new WalkSegmentEnricher(new WalkCoordResolver(), cache, slowTmap, tmapPool, metrics);

        RouteSearchDeadlineProperties deadline = new RouteSearchDeadlineProperties();
        deadline.setDeadlineMs(3000);
        deadline.setTmapMs(2000);

        // 서로 다른 좌표 → single-flight 로 합쳐지지 않고 각자 풀 슬롯을 요구한다.
        ExecutorService callers = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<RouteSearchResult>> futures = new ArrayList<>();

        for (int i = 0; i < CONCURRENCY; i++) {
            double offset = i * 0.01;
            futures.add(callers.submit(() -> {
                gate.await();
                RouteSearchContext ctx = RouteSearchContext.start(deadline.getDeadlineMs());
                try {
                    return enricher.enrich(routeWithWalk(offset),
                            127.0 + offset, 37.5, "출발",
                            127.02 + offset, 37.52, "도착", ctx, deadline);
                } finally {
                    // RouteSearchService 가 요청 끝에 하는 것과 같은 flush — 여기선 enricher 를 직접 부르므로 수동으로.
                    metrics.recordDegradations(ctx.degradations());
                }
            }));
        }
        gate.countDown();

        int enrichedCount = 0;
        for (Future<RouteSearchResult> f : futures) {
            RouteSearchResult result = f.get(10, TimeUnit.SECONDS);
            // 포화로 보강이 생략돼도 기본 경로(원본 subPath)는 항상 반환된다.
            assertThat(result.paths()).hasSize(1);
            SubPathResult walk = result.paths().get(0).subPaths().get(0);
            if (walk.walkSteps() != null && !walk.walkSteps().isEmpty()) {
                enrichedCount++;
            }
        }
        callers.shutdownNow();
        tmapPool.shutdownNow();

        double rejected = counter(registry, RouteSearchMetrics.BULKHEAD_REJECTED, "provider", "tmap");

        System.out.printf("%n=== bulkhead: TMAP pool=%d queue=%d, 동시 요청 %d건 (TMAP %dms) ===%n",
                POOL_SIZE, QUEUE_SIZE, CONCURRENCY, TMAP_LATENCY_MS);
        System.out.printf("  실제 TMAP 호출: %d회 (풀 용량 %d = size %d + queue %d)%n",
                tmapCalls.get(), POOL_SIZE + QUEUE_SIZE, POOL_SIZE, QUEUE_SIZE);
        System.out.printf("  bulkhead 거절: %,.0f회%n", rejected);
        System.out.printf("  보강 성공: %d건 / 기본 경로 반환: %d건(전부)%n", enrichedCount, CONCURRENCY);

        // 동시 실행은 풀 크기를 넘지 않는다 — 이게 격리의 핵심.
        assertThat(tmapCalls.get()).isLessThanOrEqualTo(POOL_SIZE + QUEUE_SIZE);
        // 초과분은 대기하지 않고 거절된다(과부하를 큐로 숨기지 않는다).
        assertThat(rejected).isGreaterThan(0);
        assertThat(rejected + tmapCalls.get()).isEqualTo(CONCURRENCY);
        // 거절돼도 degradation 일 뿐 — 기본 경로는 전부 반환됐다(위 루프에서 검증).
        assertThat(counter(registry, RouteSearchMetrics.DEGRADED, "reason", "bulkhead_full"))
                .isGreaterThan(0);
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private static double counter(SimpleMeterRegistry registry, String name, String tag, String value) {
        Counter c = registry.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }

    private static WalkSegment walkSegment() {
        return new WalkSegment(
                List.of(new CoordPoint(127.0, 37.5), new CoordPoint(127.02, 37.52)),
                640, 600,
                List.of(new WalkStep(0, "SP", 200, "이동", 127.0, 37.5)));
    }

    private static RouteSearchResult routeWithWalk(double offset) {
        SubPathResult walk = new SubPathResult(
                3, 10, 640.0,
                Collections.emptyList(), Collections.emptyList(),
                "출발", "도착",
                127.0 + offset, 37.5, 127.02 + offset, 37.52,
                null, null, null, null, null,
                null,
                null, null, null, null, null, null,
                null,
                null, null, null, null, null, null, null, null);
        PathInfo info = new PathInfo(10, 0, 640, 0, "출발", "도착", null);
        return new RouteSearchResult(0, 0, 0, 0, 0,
                List.of(new PathResult(3, info, List.of(walk), Collections.emptyList())));
    }
}
