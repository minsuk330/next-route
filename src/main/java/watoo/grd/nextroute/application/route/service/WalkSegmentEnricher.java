package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.route.dto.*;
import watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties;
import watoo.grd.nextroute.application.route.exception.TmapApiException;
import watoo.grd.nextroute.application.route.observability.DegradeReason;
import watoo.grd.nextroute.application.route.observability.RouteSearchContext;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.observability.SearchComponent;
import watoo.grd.nextroute.application.route.port.out.TmapPedestrianPort;
import watoo.grd.nextroute.application.route.port.out.TmapPedestrianPort.WalkSearchCommand;
import watoo.grd.nextroute.application.route.port.out.WalkSegmentCachePort;
import watoo.grd.nextroute.application.route.service.WalkCoordResolver.Resolved;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ODsay 응답의 도보 subPath(trafficType=3)에 TMAP 보행자 경로를 보강한다.
 *
 * 흐름:
 *  1. 모든 도보 subPath 식별 + WalkCoordResolver로 시작/끝 좌표 결정
 *  2. 캐시 조회 (hit이면 즉시 사용)
 *  3. miss는 좌표쌍 dedupe 후 CompletableFuture로 병렬 호출
 *  4. 정상 응답 → cache.put, 빈 응답 → cache.putNegative, 예외 → 폴백
 *  5. 결과를 SubPathResult에 주입
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalkSegmentEnricher {

    private static final Duration POSITIVE_TTL = Duration.ofDays(7);
    private static final Duration NEGATIVE_TTL = Duration.ofDays(1);
    private static final int DEFAULT_SEARCH_OPTION = 0;
    /** 시작과 도착 좌표가 이 이내면 동일 위치로 간주 (≈ 11m, 캐시 키 정밀도와 동일) */
    private static final double SAME_LOCATION_THRESHOLD = 1e-4;

    private static final String PROVIDER = "tmap";

    private final WalkCoordResolver resolver;
    private final WalkSegmentCachePort cache;
    private final TmapPedestrianPort tmapPort;
    /** TMAP 전용 bounded 풀(bulkhead). 공용 ForkJoinPool 을 쓰면 TMAP 지연이 공용 자원을 잠식한다. */
    @Qualifier("walkEnrichmentExecutor")
    private final ExecutorService walkExecutor;
    private final RouteSearchMetrics metrics;

    /**
     * 요청 간 공유되는 진행 중 TMAP 호출(single-flight). 같은 보행 구간을 동시에 miss 한 요청들이
     * 하나의 호출을 공유한다. 캐시 TTL 이 7일이라 stampede 창은 짧지만, 같은 구간을 동시에 검색하는
     * 요청이 몰리면(퇴근 시간 같은 목적지) 첫 miss 에서 호출이 폭증할 수 있다.
     */
    private final java.util.concurrent.ConcurrentMap<String, CompletableFuture<TmapResult>> inFlight =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 계측 없이 호출하는 경로(단위 테스트 등). */
    public RouteSearchResult enrich(RouteSearchResult original,
                                    double requestedStartX, double requestedStartY,
                                    String requestedStartName,
                                    double requestedEndX, double requestedEndY,
                                    String requestedEndName) {
        return enrich(original, requestedStartX, requestedStartY, requestedStartName,
                requestedEndX, requestedEndY, requestedEndName, RouteSearchContext.noop(),
                new RouteSearchDeadlineProperties());
    }

    /**
     * 시간 예산을 아는 보강(Step 2). TMAP 은 선택 보강이므로, 남은 예산이 없으면 호출하지 않고
     * 응답을 기다리는 시간도 예산 안으로 제한한다. 늦게 도착하는 응답은 캐시에만 남고 이번 응답엔 안 실린다.
     */
    public RouteSearchResult enrich(RouteSearchResult original,
                                    double requestedStartX, double requestedStartY,
                                    String requestedStartName,
                                    double requestedEndX, double requestedEndY,
                                    String requestedEndName,
                                    RouteSearchContext searchCtx,
                                    RouteSearchDeadlineProperties deadline) {
        if (original.paths() == null || original.paths().isEmpty()) {
            return original;
        }

        List<WalkContext> contexts = collectWalkContexts(original,
                requestedStartX, requestedStartY, requestedStartName,
                requestedEndX, requestedEndY, requestedEndName);
        if (contexts.isEmpty()) {
            return original;
        }

        // 도보 구간이 있는 경로만 이 보강의 적용 대상이다.
        searchCtx.requested(SearchComponent.WALK);

        Map<WalkContext, WalkSegment> resolved = new HashMap<>();
        Map<String, CompletableFuture<TmapResult>> futures = new HashMap<>();

        for (WalkContext ctx : contexts) {
            Optional<WalkSegment> hit = cache.get(ctx.key());
            if (hit.isPresent()) {
                resolved.put(ctx, hit.get());
                continue;
            }
            // 남은 예산이 거의 없으면 호출을 시작조차 하지 않는다(orphan 호출만 남기고 못 기다린다).
            if (searchCtx.remainingMillis() < deadline.getMinRemainingForOptionalMs()) {
                searchCtx.degrade(SearchComponent.WALK, DegradeReason.TIMEOUT);
                continue;
            }
            // 요청 안의 중복 제거가 1차 방어선이다. inFlight(요청 간 공유)는 완료 즉시 정리되므로
            // 여기에만 의존하면 같은 요청의 두 번째 구간이 재호출될 수 있다.
            CompletableFuture<TmapResult> future =
                    futures.computeIfAbsent(ctx.key().asKey(), k -> callTmapAsync(ctx));
            if (future == null) {
                // 풀 포화 → 이번 요청에서는 보행 상세를 생략하고 기본 경로를 그대로 낸다.
                searchCtx.degrade(SearchComponent.WALK, DegradeReason.BULKHEAD_FULL);
            }
        }

        for (WalkContext ctx : contexts) {
            if (resolved.containsKey(ctx)) continue;

            CompletableFuture<TmapResult> future = futures.get(ctx.key().asKey());
            if (future == null) continue;   // 예산 부족으로 호출하지 않음

            TmapResult tmapResult = awaitWithinBudget(future, searchCtx, deadline);
            if (tmapResult == null) {
                // 예산 안에 못 받음 → 이번 응답에서는 생략. 진행 중 호출은 캐시에만 반영된다.
                searchCtx.degrade(SearchComponent.WALK, DegradeReason.TIMEOUT);
                continue;
            }
            if (tmapResult.failed()) {
                // 폴백: 원본 유지 (resolved에 추가하지 않음 → rebuild 시 원본 사용)
                searchCtx.degrade(SearchComponent.WALK, DegradeReason.UPSTREAM_ERROR);
                continue;
            }
            WalkSegment segment = tmapResult.segment();
            if (segment.isEmpty()) {
                // TMAP 은 정상 응답했지만 보강할 내용이 없음(서비스 지역 외 등) → 원본 도보 유지.
                searchCtx.degrade(SearchComponent.WALK, DegradeReason.EMPTY_RESULT);
                cache.putNegative(ctx.key(), NEGATIVE_TTL);
            } else {
                cache.put(ctx.key(), segment, POSITIVE_TTL);
            }
            resolved.put(ctx, segment);
        }

        return rebuild(original, contexts, resolved);
    }

    /** 남은 예산과 TMAP 단계 상한 중 짧은 쪽까지만 기다린다. 초과하면 null. */
    private TmapResult awaitWithinBudget(CompletableFuture<TmapResult> future,
                                         RouteSearchContext searchCtx,
                                         RouteSearchDeadlineProperties deadline) {
        long waitMs = searchCtx.budgetMillis(deadline.getTmapMs());
        if (waitMs <= 0) {
            return null;
        }
        try {
            return future.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[WalkEnricher] TMAP 응답이 시간 예산({}ms) 안에 오지 않음 — 이번 응답에서 생략", waitMs);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return TmapResult.failure();
        }
    }

    // ── 1단계: 도보 식별 + 좌표 결정 ─────────────────────────────────────

    private List<WalkContext> collectWalkContexts(RouteSearchResult original,
                                                  double reqStartX, double reqStartY,
                                                  String reqStartName,
                                                  double reqEndX, double reqEndY,
                                                  String reqEndName) {
        List<WalkContext> contexts = new ArrayList<>();
        for (int pi = 0; pi < original.paths().size(); pi++) {
            PathResult path = original.paths().get(pi);
            if (path.subPaths() == null) continue;

            for (int si = 0; si < path.subPaths().size(); si++) {
                SubPathResult sp = path.subPaths().get(si);
                if (sp.trafficType() != 3) continue;

                SubPathResult prev = si > 0 ? path.subPaths().get(si - 1) : null;
                SubPathResult next = si < path.subPaths().size() - 1 ? path.subPaths().get(si + 1) : null;

                Resolved start = resolver.resolveStart(prev, reqStartX, reqStartY, reqStartName);
                Resolved end   = resolver.resolveEnd(next, reqEndX, reqEndY, reqEndName);

                // 출발/도착 좌표 동일 → TMAP 호출 불필요 (이동 거리 0). 원본 유지하고 skip.
                if (isSameLocation(start, end)) {
                    log.debug("[WalkEnricher] Skip same-location walk subPath at ({},{}) path={} sub={}",
                            start.x(), start.y(), pi, si);
                    continue;
                }

                WalkCacheKey key = new WalkCacheKey(
                        start.x(), start.y(), end.x(), end.y(), DEFAULT_SEARCH_OPTION);

                contexts.add(new WalkContext(pi, si, key, start, end));
            }
        }
        return contexts;
    }

    private boolean isSameLocation(Resolved start, Resolved end) {
        return Math.abs(start.x() - end.x()) <= SAME_LOCATION_THRESHOLD
                && Math.abs(start.y() - end.y()) <= SAME_LOCATION_THRESHOLD;
    }

    // ── 2단계: TMAP 비동기 호출 ──────────────────────────────────────────

    /**
     * 같은 보행 구간(WalkCacheKey)에 대한 진행 중인 호출이 있으면 그 future 를 공유한다(Step 3).
     * 이전에는 요청 <b>안</b>에서만 중복을 제거했기 때문에, 여러 요청이 동시에 같은 구간을 miss 하면
     * 그 수만큼 TMAP 을 호출했다.
     */
    private CompletableFuture<TmapResult> callTmapAsync(WalkContext ctx) {
        String key = ctx.key().asKey();
        return inFlight.computeIfAbsent(key, k -> submitTmap(ctx, k));
    }

    /** 전용 풀에 제출. 포화면 null → 호출자가 이 보강을 생략한다(거절은 실패가 아니라 degradation). */
    private CompletableFuture<TmapResult> submitTmap(WalkContext ctx, String key) {
        try {
            return CompletableFuture
                .supplyAsync(() -> {
                    try {
                        WalkSegment segment = tmapPort.search(new WalkSearchCommand(
                                ctx.key().startX(), ctx.key().startY(),
                                ctx.key().endX(), ctx.key().endY(),
                                ctx.start().name(), ctx.end().name(),
                                DEFAULT_SEARCH_OPTION));
                        return TmapResult.ok(segment);
                    } catch (TmapApiException e) {
                        log.warn("[WalkEnricher] TMAP failed for ({},{})->({},{}) — fallback: {}",
                                ctx.key().startX(), ctx.key().startY(),
                                ctx.key().endX(), ctx.key().endY(), e.getMessage());
                        return TmapResult.failure();
                    }
                }, walkExecutor)
                // 성공·실패 모두 정리 — in-flight map 누수 방지.
                .whenComplete((r, t) -> inFlight.remove(key));
        } catch (RejectedExecutionException e) {
            log.warn("[WalkEnricher] TMAP 전용 풀 포화 — 보행 보강 생략");
            metrics.recordBulkheadRejected(PROVIDER);
            return null;
        }
    }

    // ── 3단계: 응답 재구성 ───────────────────────────────────────────────

    private RouteSearchResult rebuild(RouteSearchResult original,
                                      List<WalkContext> contexts,
                                      Map<WalkContext, WalkSegment> resolved) {
        Map<Integer, Map<Integer, SubPathResult>> overrides = new HashMap<>();
        for (WalkContext ctx : contexts) {
            WalkSegment segment = resolved.get(ctx);
            if (segment == null || segment.isEmpty()) continue;

            SubPathResult original_sp = original.paths().get(ctx.pathIdx()).subPaths().get(ctx.subPathIdx());
            SubPathResult enriched = applyWalkSegment(original_sp, ctx.start(), ctx.end(), segment);
            overrides.computeIfAbsent(ctx.pathIdx(), k -> new HashMap<>())
                    .put(ctx.subPathIdx(), enriched);
        }

        List<PathResult> newPaths = new ArrayList<>(original.paths().size());
        for (int pi = 0; pi < original.paths().size(); pi++) {
            PathResult path = original.paths().get(pi);
            Map<Integer, SubPathResult> pathOverrides = overrides.get(pi);
            if (pathOverrides == null || pathOverrides.isEmpty()) {
                newPaths.add(path);
                continue;
            }
            List<SubPathResult> newSubs = new ArrayList<>(path.subPaths().size());
            for (int si = 0; si < path.subPaths().size(); si++) {
                newSubs.add(pathOverrides.getOrDefault(si, path.subPaths().get(si)));
            }
            newPaths.add(new PathResult(path.pathType(), path.info(), newSubs, path.laneGraphics()));
        }

        return new RouteSearchResult(
                original.searchType(), original.busCount(), original.subwayCount(),
                original.trainCount(), original.airCount(), newPaths);
    }

    private SubPathResult applyWalkSegment(SubPathResult sp, Resolved start, Resolved end, WalkSegment segment) {
        // 출구 정보: exitNo가 null이면 좌표도 null로 (지하철 인접일 때만 의미 있음)
        Double startExitX = start.exitNo() != null ? start.x() : null;
        Double startExitY = start.exitNo() != null ? start.y() : null;
        Double endExitX   = end.exitNo() != null ? end.x() : null;
        Double endExitY   = end.exitNo() != null ? end.y() : null;

        return new SubPathResult(
                sp.trafficType(), sp.sectionTime(), sp.distance(),
                sp.lanes(), sp.stations(),
                start.name(), end.name(),
                start.x(), start.y(), end.x(), end.y(),
                sp.trainType(), sp.payment(), sp.startId(), sp.way(), sp.wayCode(),
                segment.polyline(),
                start.exitNo(), startExitX, startExitY,
                end.exitNo(),   endExitX,   endExitY,
                segment.steps(),
                sp.startLocalStationID(), sp.endLocalStationID(),
                sp.startArsID(), sp.endArsID(), sp.endID(),
                segment.totalTime() > 0 ? segment.totalTime() : null,
                sp.transferArrivals()
        );
    }

    // ── 내부 record ──────────────────────────────────────────────────────

    private record WalkContext(
            int pathIdx,
            int subPathIdx,
            WalkCacheKey key,
            Resolved start,
            Resolved end
    ) {}

    private record TmapResult(WalkSegment segment, boolean failed) {
        static TmapResult ok(WalkSegment segment) { return new TmapResult(segment, false); }
        static TmapResult failure() { return new TmapResult(null, true); }
    }
}
