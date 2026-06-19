package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.route.dto.*;
import watoo.grd.nextroute.application.route.exception.TmapApiException;
import watoo.grd.nextroute.application.route.port.out.TmapPedestrianPort;
import watoo.grd.nextroute.application.route.port.out.TmapPedestrianPort.WalkSearchCommand;
import watoo.grd.nextroute.application.route.port.out.WalkSegmentCachePort;
import watoo.grd.nextroute.application.route.service.WalkCoordResolver.Resolved;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

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

    private final WalkCoordResolver resolver;
    private final WalkSegmentCachePort cache;
    private final TmapPedestrianPort tmapPort;

    public RouteSearchResult enrich(RouteSearchResult original,
                                    double requestedStartX, double requestedStartY,
                                    String requestedStartName,
                                    double requestedEndX, double requestedEndY,
                                    String requestedEndName) {
        if (original.paths() == null || original.paths().isEmpty()) {
            return original;
        }

        List<WalkContext> contexts = collectWalkContexts(original,
                requestedStartX, requestedStartY, requestedStartName,
                requestedEndX, requestedEndY, requestedEndName);
        if (contexts.isEmpty()) {
            return original;
        }

        Map<WalkContext, WalkSegment> resolved = new HashMap<>();
        Map<String, CompletableFuture<TmapResult>> futures = new HashMap<>();

        for (WalkContext ctx : contexts) {
            Optional<WalkSegment> hit = cache.get(ctx.key());
            if (hit.isPresent()) {
                resolved.put(ctx, hit.get());
                continue;
            }
            futures.computeIfAbsent(ctx.key().asKey(), k -> callTmapAsync(ctx));
        }

        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0])).join();
        }

        for (WalkContext ctx : contexts) {
            if (resolved.containsKey(ctx)) continue;

            TmapResult tmapResult = futures.get(ctx.key().asKey()).join();
            if (tmapResult.failed()) {
                // 폴백: 원본 유지 (resolved에 추가하지 않음 → rebuild 시 원본 사용)
                continue;
            }
            WalkSegment segment = tmapResult.segment();
            if (segment.isEmpty()) {
                cache.putNegative(ctx.key(), NEGATIVE_TTL);
            } else {
                cache.put(ctx.key(), segment, POSITIVE_TTL);
            }
            resolved.put(ctx, segment);
        }

        return rebuild(original, contexts, resolved);
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

    private CompletableFuture<TmapResult> callTmapAsync(WalkContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
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
        });
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
