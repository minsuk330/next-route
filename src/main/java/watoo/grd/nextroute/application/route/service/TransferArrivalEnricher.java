package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.config.BusCollectorProperties;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.route.config.MlPredictorProperties;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.dto.*;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlFeatureVector;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPrediction;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.Outcome;
import watoo.grd.nextroute.application.route.service.TransferStopResolver.RouteResolution;
import watoo.grd.nextroute.application.route.service.TransferStopResolver.SeqResolution;
import watoo.grd.nextroute.application.route.service.TransferStopResolver.StopResolution;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

/**
 * 버스 승차 지점별 도착예측 보강.
 * dependency-aware wave fan-out: wave 내 항목은 병렬 dedupe, wave 간에는 직전 결과를 타임라인에 반영.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferArrivalEnricher {

    private static final int TRAFFIC_BUS = 2;
    private static final int TRAFFIC_WALK = 3;
    private static final long STALE_BEFORE_SEC = 120;
    private static final long STALE_AFTER_SEC = 60;

    private final TransferArrivalProperties props;
    private final MlPredictorProperties mlProps;
    private final SearchTimeBusQueryPort busPort;
    private final MlArrivalPredictorPort mlPort;
    private final TransferStopResolver resolver;
    private final MlFeatureVectorBuilder featureBuilder;
    private final BusCollectorProperties collectorProps;
    private final java.time.Clock clock;

    // ── 공개 API ────────────────────────────────────────────────────────────

    public RouteSearchResult enrich(RouteSearchResult result, Instant searchStartedAt) {
        if (result.paths() == null || result.paths().isEmpty()) return result;

        List<SubPathCtx> all = collectContexts(result);
        if (all.isEmpty()) return result;

        if (!props.isEnabled()) {
            return rebuild(result, all, buildDisabledResults(all, searchStartedAt));
        }

        // 정적 resolve (wave 무관)
        resolveStatic(all);

        // enrich 진입 기준 deadline. 초과 시 미처리 wave는 status=ERROR.
        Instant deadline = props.getDeadlineMs() > 0
                ? clock.instant().plusMillis(props.getDeadlineMs())
                : null;

        int maxWave = all.stream().mapToInt(c -> c.waveIndex).max().orElse(-1);

        // pathIdx → (직전 wave 대표 lane 예측 도착시각, 직전 버스 subPath 인덱스, 대표 laneIndex, conditional)
        Map<Integer, PrevWaveInfo> prevWave = new HashMap<>();

        Map<Integer, Map<Integer, List<TransferArrival>>> resultMap = new HashMap<>();

        // 검색당 외부 호출 상한(provider fan-out cap). 0 이하면 무제한.
        java.util.concurrent.atomic.AtomicInteger callBudget = new java.util.concurrent.atomic.AtomicInteger(
                props.getMaxExternalCallsPerSearch() > 0 ? props.getMaxExternalCallsPerSearch() : Integer.MAX_VALUE);

        for (int wave = 0; wave <= maxWave; wave++) {
            int w = wave;
            List<SubPathCtx> waveCtxs = all.stream().filter(c -> c.waveIndex == w).toList();
            if (waveCtxs.isEmpty()) continue;

            if (deadline != null && clock.instant().isAfter(deadline)) {
                // deadline 초과 — 외부 호출 없이 이 wave 전부 ERROR
                fillError(waveCtxs, searchStartedAt, resultMap);
                continue;
            }

            processWave(wave, waveCtxs, result, searchStartedAt, prevWave, resultMap, callBudget, deadline);
        }

        return rebuild(result, all, resultMap);
    }

    /**
     * 검색당 외부 호출 상한·deadline 내에서 도착정보 조회.
     * deadline 초과→ERROR, 상한 소진→LIMITED. cache hit은 provider 미호출이라 budget 복원.
     */
    private BusQueryResult<BusArrivalInfo> callArr(String stopId, String routeId, int ord,
                                                   java.util.concurrent.atomic.AtomicInteger callBudget,
                                                   Instant deadline) {
        if (deadline != null && clock.instant().isAfter(deadline)) {
            log.debug("[TransferEnricher] deadline exceeded — skip arr {}:{}", stopId, routeId);
            return BusQueryResult.error();
        }
        if (callBudget.getAndDecrement() <= 0) {
            callBudget.incrementAndGet();
            log.debug("[TransferEnricher] per-search call budget exhausted — skip arr {}:{}", stopId, routeId);
            return BusQueryResult.limited();
        }
        BusQueryResult<BusArrivalInfo> r = busPort.getArrInfoByStop(stopId, routeId, ord);
        if (r.cacheHit()) callBudget.incrementAndGet();   // cache hit은 provider 호출 아님 → cap 미소모
        return r;
    }

    /**
     * 검색당 외부 호출 상한·deadline 내에서 위치정보 조회.
     * deadline 초과→ERROR, 상한 소진→LIMITED. cache hit은 budget 복원.
     */
    private BusQueryResult<BusPositionInfo> callPos(String routeId,
                                                    java.util.concurrent.atomic.AtomicInteger callBudget,
                                                    Instant deadline) {
        if (deadline != null && clock.instant().isAfter(deadline)) {
            log.debug("[TransferEnricher] deadline exceeded — skip pos {}", routeId);
            return BusQueryResult.error();
        }
        if (callBudget.getAndDecrement() <= 0) {
            callBudget.incrementAndGet();
            log.debug("[TransferEnricher] per-search call budget exhausted — skip pos {}", routeId);
            return BusQueryResult.limited();
        }
        BusQueryResult<BusPositionInfo> r = busPort.getBusPosByRtid(routeId);
        if (r.cacheHit()) callBudget.incrementAndGet();
        return r;
    }

    private TransferArrival.Status mapOutcome(Outcome outcome) {
        return switch (outcome) {
            case BLOCKED -> TransferArrival.Status.BLOCKED;
            case LIMITED -> TransferArrival.Status.LIMITED;
            default -> TransferArrival.Status.ERROR;
        };
    }

    private void fillError(List<SubPathCtx> ctxs, Instant calculatedAt,
                           Map<Integer, Map<Integer, List<TransferArrival>>> resultMap) {
        for (SubPathCtx ctx : ctxs) {
            List<TransferArrival> lanes = new ArrayList<>();
            for (LaneCtx lc : ctx.laneContexts) {
                lanes.add(noResult(lc.routeId, lc.laneIdx,
                        TransferArrival.Source.NONE, TransferArrival.Status.ERROR,
                        calculatedAt, null));
            }
            resultMap.computeIfAbsent(ctx.pathIdx, k -> new HashMap<>())
                    .put(ctx.subPathIdx, lanes);
        }
    }

    // ── 1단계: context 수집 ─────────────────────────────────────────────────

    private List<SubPathCtx> collectContexts(RouteSearchResult result) {
        List<SubPathCtx> list = new ArrayList<>();
        for (int pi = 0; pi < result.paths().size(); pi++) {
            PathResult path = result.paths().get(pi);
            if (path.subPaths() == null) continue;
            int busCount = 0;
            for (int si = 0; si < path.subPaths().size(); si++) {
                SubPathResult sp = path.subPaths().get(si);
                if (sp.trafficType() != TRAFFIC_BUS) continue;
                int lanes = sp.lanes() != null ? sp.lanes().size() : 0;
                if (lanes == 0) continue;
                SubPathCtx ctx = new SubPathCtx(pi, si, busCount, sp.lanes().size());
                list.add(ctx);
                busCount++;
            }
        }
        return list;
    }

    // ── 2단계: 정적 resolve ─────────────────────────────────────────────────

    private void resolveStatic(List<SubPathCtx> all) {
        for (SubPathCtx ctx : all) {
            // stopId는 context에서 직접 꺼내야 하므로 여기서는 준비만
            // 실제 resolve는 processWave에서 수행 (원본 subPath 접근 필요)
        }
    }

    // ── 3단계: wave 처리 ────────────────────────────────────────────────────

    private void processWave(
            int wave,
            List<SubPathCtx> waveCtxs,
            RouteSearchResult result,
            Instant searchStartedAt,
            Map<Integer, PrevWaveInfo> prevWave,
            Map<Integer, Map<Integer, List<TransferArrival>>> resultMap,
            java.util.concurrent.atomic.AtomicInteger callBudget,
            Instant deadline) {

        Instant calculatedAt = searchStartedAt;

        // 3a: estimatedUserArrivalAt 계산
        for (SubPathCtx ctx : waveCtxs) {
            PathResult path = result.paths().get(ctx.pathIdx);
            SubPathResult busSubPath = path.subPaths().get(ctx.subPathIdx);

            if (wave == 0) {
                long sumSec = 0;
                boolean approx = false;
                for (int k = 0; k < ctx.subPathIdx; k++) {
                    SubPathResult sp = path.subPaths().get(k);
                    if (sp.trafficType() == 1) approx = true; // 지하철 대기시간 미반영
                    sumSec += travelSeconds(sp);
                }
                ctx.estimatedUserArrivalAt = searchStartedAt.plusSeconds(sumSec);
                ctx.precedingSubway = approx;
                ctx.basisLaneIndex = null;
                ctx.conditional = false;
            } else {
                PrevWaveInfo prev = prevWave.get(ctx.pathIdx);
                if (prev == null || !prev.boardable) {
                    ctx.upstreamUnavailable = true;
                    ctx.estimatedUserArrivalAt = searchStartedAt; // 사용 안 함
                    continue;
                }
                // 직전 버스 ride time + 사이 이동 시간
                SubPathResult prevBusSp = path.subPaths().get(prev.subPathIdx);
                long sumSec = prevBusSp.sectionTime() * 60L;
                for (int k = prev.subPathIdx + 1; k < ctx.subPathIdx; k++) {
                    sumSec += travelSeconds(path.subPaths().get(k));
                }
                ctx.estimatedUserArrivalAt = prev.predictedArrivalAt.plusSeconds(sumSec);
                ctx.basisLaneIndex = prev.basisLaneIndex;
                ctx.conditional = prev.conditional;
            }

            // static resolve 실행 (subPath 접근 필요)
            SubPathResult sp = busSubPath;
            Optional<StopResolution> stopRes = resolver.resolveStop(sp.startLocalStationID());
            if (stopRes.isEmpty()) {
                ctx.stopId = null;
            } else {
                ctx.stopId = stopRes.get().stopId();
            }

            for (int li = 0; li < sp.lanes().size(); li++) {
                LaneResult lane = sp.lanes().get(li);
                LaneCtx lc = ctx.laneContexts.get(li);
                Optional<RouteResolution> routeRes = resolver.resolveRoute(lane.busLocalBlID(), lane.busNo());
                if (routeRes.isEmpty()) {
                    lc.routeId = null;
                    lc.routeName = null;
                } else {
                    lc.routeId = routeRes.get().routeId();
                    lc.routeName = routeRes.get().routeName();
                    if (ctx.stopId != null && lc.routeId != null) {
                        SeqResolution seqRes = resolver.resolveSeq(lc.routeId, ctx.stopId);
                        lc.seqCandidates = seqRes.candidates();
                        // 사용자가 노선·정류장을 특정한 상황이라 seq는 단일. getArrInfoByRoute(ord) 입력으로 미리 확정.
                        lc.targetSeq = lc.seqCandidates.size() == 1 ? lc.seqCandidates.get(0) : null;
                    }
                }
            }
        }

        // 3b: arrival API 조회 — (stopId, routeId, ord) 단위 dedupe (정류장·노선·순번 특정 상황)
        Map<String, BusQueryResult<BusArrivalInfo>> arrCache = new HashMap<>();
        for (SubPathCtx ctx : waveCtxs) {
            if (ctx.upstreamUnavailable || ctx.stopId == null) continue;
            for (LaneCtx lc : ctx.laneContexts) {
                if (lc.routeId == null || lc.targetSeq == null) continue;  // seq 미확정 lane은 조회 불가
                String key = ctx.stopId + ":" + lc.routeId + ":" + lc.targetSeq;
                BusQueryResult<BusArrivalInfo> r = arrCache.get(key);
                if (r == null) {
                    r = callArr(ctx.stopId, lc.routeId, lc.targetSeq, callBudget, deadline);
                    arrCache.put(key, r);
                }
                lc.arrOutcome = r.outcome();
                if (r.isOk()) {
                    lc.arrMatched = r.data().stream()
                            .filter(a -> lc.routeId.equals(a.routeId()))
                            .findFirst().orElse(null);
                }
            }
        }

        // 3c: REALTIME 판정 + ML 후보 수집
        Set<String> mlRouteIds = new HashSet<>();
        for (SubPathCtx ctx : waveCtxs) {
            if (ctx.upstreamUnavailable) continue;
            for (LaneCtx lc : ctx.laneContexts) {
                if (lc.routeId == null) continue;
                if (lc.targetSeq == null) continue;              // 3f에서 STOP_MAPPING_FAILED
                if (lc.arrOutcome != Outcome.OK) continue;       // 3f에서 mapOutcome (차단/제한/오류)

                // REALTIME 판정 (응답은 해당 노선 1건)
                if (lc.arrMatched != null) {
                    Optional<Instant> realtimeAt = earliestRealtimeArrival(lc.arrMatched, ctx.estimatedUserArrivalAt);
                    if (realtimeAt.isPresent()) {
                        String vehicleId = pickVehicleId(lc.arrMatched, realtimeAt.get());
                        lc.realtimeResult = buildAvailable(
                                lc.routeId, lc.laneIdx,
                                TransferArrival.Source.REALTIME,
                                calculatedAt, ctx.estimatedUserArrivalAt, realtimeAt.get(),
                                vehicleId, null,
                                ctx.basisLaneIndex, ctx.conditional, ctx.precedingSubway);
                        continue;
                    }
                }

                // ML 후보 (top-30만)
                if (isTop30(lc.routeName)) {
                    mlRouteIds.add(lc.routeId);
                } else {
                    lc.unsupportedRoute = true;
                }
            }
        }

        // 3d: position API dedupe (ML 후보 routeId만). ML 꺼져 있으면 조회 불필요(MODEL_UNAVAILABLE 확정)
        Map<String, List<BusPositionInfo>> positionMap = new HashMap<>();
        Map<String, Outcome> positionOutcomeMap = new HashMap<>();
        if (mlProps.isEnabled()) {
            for (String routeId : mlRouteIds) {
                BusQueryResult<BusPositionInfo> posRes = callPos(routeId, callBudget, deadline);
                positionMap.put(routeId, posRes.data());
                positionOutcomeMap.put(routeId, posRes.outcome());
            }
        }

        // 3e: ML feature + batch call
        List<MlFeatureVector> mlVectors = new ArrayList<>();
        // requestId: pathIdx:subPathIdx:laneIdx:vehicleId
        for (SubPathCtx ctx : waveCtxs) {
            if (ctx.upstreamUnavailable) continue;
            for (LaneCtx lc : ctx.laneContexts) {
                if (lc.realtimeResult != null || lc.routeId == null || !isTop30(lc.routeName)) continue;
                if (lc.targetSeq == null || lc.arrOutcome != Outcome.OK) continue;

                List<BusPositionInfo> positions = positionMap.getOrDefault(lc.routeId, List.of());
                for (BusPositionInfo pos : positions) {
                    if (!isValidVehicle(pos, lc.targetSeq, calculatedAt)) continue;
                    String reqId = ctx.pathIdx + ":" + ctx.subPathIdx + ":" + lc.laneIdx + ":" + pos.vehicleId();
                    featureBuilder.build(reqId, pos, lc.targetSeq, lc.routeId)
                            .ifPresent(mlVectors::add);
                }
            }
        }

        Map<String, MlPrediction> mlResults = new HashMap<>();
        if (!mlVectors.isEmpty() && mlProps.isEnabled()) {
            try {
                mlPort.predict(mlVectors).forEach(p -> mlResults.put(p.requestId(), p));
            } catch (Exception e) {
                log.warn("[TransferEnricher] ML predict failed: {}", e.getMessage());
            }
        }

        // 3f: 결과 확정
        Map<Integer, Instant> pathBestArrivalThisWave = new HashMap<>();
        Map<Integer, Integer> pathBestLaneThisWave = new HashMap<>();
        Map<Integer, Boolean> pathConditionalThisWave = new HashMap<>();
        Map<Integer, Boolean> pathBoardableThisWave = new HashMap<>();

        for (SubPathCtx ctx : waveCtxs) {
            List<TransferArrival> laneResults = new ArrayList<>();

            if (ctx.upstreamUnavailable) {
                for (LaneCtx lc : ctx.laneContexts) {
                    laneResults.add(noResult(lc.routeId, lc.laneIdx,
                            TransferArrival.Source.NONE, TransferArrival.Status.UPSTREAM_UNAVAILABLE,
                            calculatedAt, ctx.estimatedUserArrivalAt));
                }
                resultMap.computeIfAbsent(ctx.pathIdx, k -> new HashMap<>())
                        .put(ctx.subPathIdx, laneResults);
                continue;
            }

            for (LaneCtx lc : ctx.laneContexts) {
                TransferArrival ta;

                if (ctx.stopId == null) {
                    ta = noResult(lc.routeId, lc.laneIdx,
                            TransferArrival.Source.NONE, TransferArrival.Status.STOP_MAPPING_FAILED,
                            calculatedAt, ctx.estimatedUserArrivalAt);
                } else if (lc.routeId == null) {
                    ta = noResult(null, lc.laneIdx,
                            TransferArrival.Source.NONE, TransferArrival.Status.UNSUPPORTED_ROUTE,
                            calculatedAt, ctx.estimatedUserArrivalAt);
                } else if (lc.targetSeq == null) {
                    // seq 유일 확정 실패 — getArrInfoByRoute(ord) 호출 불가
                    ta = noResult(lc.routeId, lc.laneIdx,
                            TransferArrival.Source.NONE, TransferArrival.Status.STOP_MAPPING_FAILED,
                            calculatedAt, ctx.estimatedUserArrivalAt);
                } else if (lc.arrOutcome != Outcome.OK) {
                    // 도착 조회 차단/제한/오류 — "버스 없음"과 구분
                    ta = noResult(lc.routeId, lc.laneIdx,
                            TransferArrival.Source.NONE, mapOutcome(lc.arrOutcome),
                            calculatedAt, ctx.estimatedUserArrivalAt);
                } else if (lc.realtimeResult != null) {
                    ta = lc.realtimeResult;
                } else if (lc.unsupportedRoute) {
                    ta = noResult(lc.routeId, lc.laneIdx,
                            TransferArrival.Source.NONE, TransferArrival.Status.UNSUPPORTED_ROUTE,
                            calculatedAt, ctx.estimatedUserArrivalAt);
                } else if (!mlProps.isEnabled()) {
                    ta = noResult(lc.routeId, lc.laneIdx,
                            TransferArrival.Source.NONE, TransferArrival.Status.MODEL_UNAVAILABLE,
                            calculatedAt, ctx.estimatedUserArrivalAt);
                } else if (positionOutcomeMap.getOrDefault(lc.routeId, Outcome.OK) != Outcome.OK) {
                    // position 조회 차단/제한/오류 — MODEL 분기 불가
                    ta = noResult(lc.routeId, lc.laneIdx,
                            TransferArrival.Source.NONE, mapOutcome(positionOutcomeMap.get(lc.routeId)),
                            calculatedAt, ctx.estimatedUserArrivalAt);
                } else {
                    // ML 결과에서 가장 이른 boardable 차량 탐색
                    ta = pickMlResult(ctx, lc, positionMap, mlResults, calculatedAt);
                }

                laneResults.add(ta);
            }

            resultMap.computeIfAbsent(ctx.pathIdx, k -> new HashMap<>())
                    .put(ctx.subPathIdx, laneResults);

            // 다음 wave 타임라인 업데이트
            updatePrevWave(ctx.pathIdx, ctx.subPathIdx, laneResults,
                    pathBestArrivalThisWave, pathBestLaneThisWave,
                    pathConditionalThisWave, pathBoardableThisWave);
        }

        // prevWave 갱신
        for (SubPathCtx ctx : waveCtxs) {
            Instant best = pathBestArrivalThisWave.get(ctx.pathIdx);
            boolean boardable = Boolean.TRUE.equals(pathBoardableThisWave.get(ctx.pathIdx));
            Integer bestLane = pathBestLaneThisWave.get(ctx.pathIdx);
            boolean conditional = Boolean.TRUE.equals(pathConditionalThisWave.get(ctx.pathIdx));
            prevWave.put(ctx.pathIdx, new PrevWaveInfo(
                    boardable ? best : null, ctx.subPathIdx, bestLane, conditional, boardable));
        }
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private long travelSeconds(SubPathResult sp) {
        if (sp.trafficType() == TRAFFIC_WALK && sp.walkTotalTimeSeconds() != null) {
            return sp.walkTotalTimeSeconds();
        }
        return sp.sectionTime() * 60L;
    }

    private Optional<Instant> earliestRealtimeArrival(BusArrivalInfo ai, Instant userAt) {
        Optional<Instant> mkTm = BusTimeParser.parse(ai.dataTimestamp());
        if (mkTm.isEmpty()) return Optional.empty();

        Instant base = mkTm.get();
        Instant best = null;

        if (ai.predictTime1() != null) {
            Instant t = base.plusSeconds(ai.predictTime1());
            if (!t.isBefore(userAt) && (best == null || t.isBefore(best))) best = t;
        }
        if (ai.predictTime2() != null) {
            Instant t = base.plusSeconds(ai.predictTime2());
            if (!t.isBefore(userAt) && (best == null || t.isBefore(best))) best = t;
        }
        return Optional.ofNullable(best);
    }

    private String pickVehicleId(BusArrivalInfo ai, Instant realtimeAt) {
        Optional<Instant> mkTm = BusTimeParser.parse(ai.dataTimestamp());
        if (mkTm.isEmpty()) return null;
        Instant base = mkTm.get();
        if (ai.predictTime1() != null && base.plusSeconds(ai.predictTime1()).equals(realtimeAt)) return ai.vehicleId1();
        if (ai.predictTime2() != null && base.plusSeconds(ai.predictTime2()).equals(realtimeAt)) return ai.vehicleId2();
        return null;
    }

    private boolean isValidVehicle(BusPositionInfo pos, int targetSeq, Instant calculatedAt) {
        if (!"1".equals(pos.isRunYn())) return false;
        if (pos.sectionOrder() == null || pos.dataTm() == null) return false;
        if (pos.sectionOrder() > targetSeq) return false;
        Optional<Instant> snapOpt = BusTimeParser.parse(pos.dataTm());
        if (snapOpt.isEmpty()) return false;
        Instant snap = snapOpt.get();
        return !snap.isBefore(calculatedAt.minusSeconds(STALE_BEFORE_SEC))
                && !snap.isAfter(calculatedAt.plusSeconds(STALE_AFTER_SEC));
    }

    private boolean isTop30(String routeName) {
        if (routeName == null) return false;
        return collectorProps.getTargetRouteNames().contains(routeName);
    }

    private TransferArrival pickMlResult(
            SubPathCtx ctx, LaneCtx lc,
            Map<String, List<BusPositionInfo>> positionMap,
            Map<String, MlPrediction> mlResults,
            Instant calculatedAt) {

        List<BusPositionInfo> positions = positionMap.getOrDefault(lc.routeId, List.of());
        if (positions.isEmpty()) {
            return noResult(lc.routeId, lc.laneIdx,
                    TransferArrival.Source.MODEL, TransferArrival.Status.NO_VEHICLE,
                    calculatedAt, ctx.estimatedUserArrivalAt);
        }

        Instant bestArrival = null;
        String bestVehicle = null;
        String bestModelVersion = null;

        for (BusPositionInfo pos : positions) {
            if (!isValidVehicle(pos, lc.targetSeq, calculatedAt)) continue;
            String reqId = ctx.pathIdx + ":" + ctx.subPathIdx + ":" + lc.laneIdx + ":" + pos.vehicleId();
            MlPrediction pred = mlResults.get(reqId);
            if (pred == null) continue;
            if (pred.status() != MlArrivalPredictorPort.MlPredictionStatus.AVAILABLE) continue;
            if (pred.secondsToArrival() == null) continue;

            Optional<Instant> snapOpt = BusTimeParser.parse(pos.dataTm());
            if (snapOpt.isEmpty()) continue;
            Instant modelAt = snapOpt.get().plusSeconds(pred.secondsToArrival().longValue());
            if (modelAt.isBefore(ctx.estimatedUserArrivalAt)) continue;
            if (bestArrival == null || modelAt.isBefore(bestArrival)) {
                bestArrival = modelAt;
                bestVehicle = pos.vehicleId();
                bestModelVersion = pred.modelVersion();
            }
        }

        if (bestArrival == null) {
            return noResult(lc.routeId, lc.laneIdx,
                    TransferArrival.Source.MODEL, TransferArrival.Status.NO_VEHICLE,
                    calculatedAt, ctx.estimatedUserArrivalAt);
        }

        return buildAvailable(lc.routeId, lc.laneIdx,
                TransferArrival.Source.MODEL,
                calculatedAt, ctx.estimatedUserArrivalAt, bestArrival,
                bestVehicle, bestModelVersion,
                ctx.basisLaneIndex, ctx.conditional, ctx.precedingSubway);
    }

    private TransferArrival buildAvailable(
            String routeId, int laneIdx, TransferArrival.Source source,
            Instant calculatedAt, Instant userAt, Instant predictedAt,
            String vehicleId, String modelVersion,
            Integer basisLaneIndex, boolean conditional, boolean precedingSubway) {

        long waitSec = Math.max(0, Duration.between(userAt, predictedAt).toSeconds());
        TransferArrival.Status status = precedingSubway
                ? TransferArrival.Status.ARRIVAL_TIME_APPROXIMATE
                : TransferArrival.Status.AVAILABLE;
        return new TransferArrival(routeId, laneIdx, source, status,
                calculatedAt, userAt, predictedAt, waitSec,
                vehicleId, modelVersion, basisLaneIndex, conditional);
    }

    private TransferArrival noResult(String routeId, int laneIdx,
                                     TransferArrival.Source source, TransferArrival.Status status,
                                     Instant calculatedAt, Instant userAt) {
        return new TransferArrival(routeId, laneIdx, source, status,
                calculatedAt, userAt, null, null, null, null, null, null);
    }

    private void updatePrevWave(
            int pathIdx, int subPathIdx,
            List<TransferArrival> laneResults,
            Map<Integer, Instant> bestArrival,
            Map<Integer, Integer> bestLane,
            Map<Integer, Boolean> conditional,
            Map<Integer, Boolean> boardable) {

        List<TransferArrival> boardables = laneResults.stream()
                .filter(ta -> ta.predictedArrivalAt() != null &&
                        (ta.status() == TransferArrival.Status.AVAILABLE ||
                         ta.status() == TransferArrival.Status.ARRIVAL_TIME_APPROXIMATE))
                .toList();

        if (boardables.isEmpty()) {
            boardable.put(pathIdx, false);
            return;
        }

        boardable.put(pathIdx, true);
        conditional.put(pathIdx, boardables.size() > 1);

        TransferArrival rep = boardables.stream()
                .min(Comparator.comparing(TransferArrival::predictedArrivalAt))
                .orElseThrow();
        bestArrival.put(pathIdx, rep.predictedArrivalAt());
        bestLane.put(pathIdx, rep.laneIndex());
    }

    // ── DISABLED 처리 ────────────────────────────────────────────────────────

    private Map<Integer, Map<Integer, List<TransferArrival>>> buildDisabledResults(
            List<SubPathCtx> all, Instant calculatedAt) {
        Map<Integer, Map<Integer, List<TransferArrival>>> map = new HashMap<>();
        for (SubPathCtx ctx : all) {
            List<TransferArrival> laneResults = IntStream.range(0, ctx.laneCount)
                    .mapToObj(li -> new TransferArrival(
                            null, li, TransferArrival.Source.NONE, TransferArrival.Status.DISABLED,
                            calculatedAt, null, null, null, null, null, null, null))
                    .toList();
            map.computeIfAbsent(ctx.pathIdx, k -> new HashMap<>())
                    .put(ctx.subPathIdx, laneResults);
        }
        return map;
    }

    // ── 재구성 ───────────────────────────────────────────────────────────────

    private RouteSearchResult rebuild(
            RouteSearchResult original,
            List<SubPathCtx> contexts,
            Map<Integer, Map<Integer, List<TransferArrival>>> resultMap) {

        // path별 subPath 오버라이드 맵
        Map<Integer, Map<Integer, List<TransferArrival>>> overrides = resultMap;

        List<PathResult> newPaths = new ArrayList<>(original.paths().size());
        for (int pi = 0; pi < original.paths().size(); pi++) {
            PathResult path = original.paths().get(pi);
            Map<Integer, List<TransferArrival>> pathOverrides = overrides.get(pi);

            if (pathOverrides == null || pathOverrides.isEmpty()) {
                newPaths.add(path);
                continue;
            }

            List<SubPathResult> newSubs = new ArrayList<>(path.subPaths().size());
            for (int si = 0; si < path.subPaths().size(); si++) {
                SubPathResult sp = path.subPaths().get(si);
                List<TransferArrival> arrivals = pathOverrides.get(si);
                if (arrivals == null) {
                    newSubs.add(sp);
                } else {
                    newSubs.add(withTransferArrivals(sp, arrivals));
                }
            }
            newPaths.add(new PathResult(path.pathType(), path.info(), newSubs, path.laneGraphics()));
        }

        return new RouteSearchResult(
                original.searchType(), original.busCount(), original.subwayCount(),
                original.trainCount(), original.airCount(), newPaths);
    }

    private SubPathResult withTransferArrivals(SubPathResult sp, List<TransferArrival> arrivals) {
        return new SubPathResult(
                sp.trafficType(), sp.sectionTime(), sp.distance(),
                sp.lanes(), sp.stations(), sp.startName(), sp.endName(),
                sp.startX(), sp.startY(), sp.endX(), sp.endY(),
                sp.trainType(), sp.payment(), sp.startId(), sp.way(), sp.wayCode(),
                sp.polyline(),
                sp.startExitNo(), sp.startExitX(), sp.startExitY(),
                sp.endExitNo(), sp.endExitX(), sp.endExitY(),
                sp.walkSteps(),
                sp.startLocalStationID(), sp.endLocalStationID(),
                sp.startArsID(), sp.endArsID(), sp.endID(),
                sp.walkTotalTimeSeconds(), arrivals
        );
    }

    // ── 내부 상태 클래스 ──────────────────────────────────────────────────────

    static class SubPathCtx {
        final int pathIdx, subPathIdx, waveIndex, laneCount;
        String stopId;
        Instant estimatedUserArrivalAt;
        Integer basisLaneIndex;
        boolean conditional;
        boolean upstreamUnavailable;
        boolean precedingSubway;
        final List<LaneCtx> laneContexts;

        SubPathCtx(int pathIdx, int subPathIdx, int waveIndex, int laneCount) {
            this.pathIdx = pathIdx;
            this.subPathIdx = subPathIdx;
            this.waveIndex = waveIndex;
            this.laneCount = laneCount;
            this.laneContexts = new ArrayList<>();
            for (int i = 0; i < laneCount; i++) {
                laneContexts.add(new LaneCtx(i));
            }
        }
    }

    static class LaneCtx {
        final int laneIdx;
        String routeId;
        String routeName;
        List<Integer> seqCandidates = List.of();
        Integer targetSeq;
        boolean unsupportedRoute;
        Outcome arrOutcome = Outcome.OK;        // 도착 조회 결과(BLOCKED/LIMITED/ERROR면 lane status로 반영)
        BusArrivalInfo arrMatched;              // 해당 노선 도착 1건(OK일 때만)
        TransferArrival realtimeResult;

        LaneCtx(int laneIdx) { this.laneIdx = laneIdx; }
    }

    record PrevWaveInfo(Instant predictedArrivalAt, int subPathIdx, Integer basisLaneIndex,
                        boolean conditional, boolean boardable) {}
}
