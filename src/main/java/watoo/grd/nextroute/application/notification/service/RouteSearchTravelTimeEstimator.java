package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.notification.config.RouteArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.port.out.RouteOutOfServiceException;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator;
import watoo.grd.nextroute.application.route.dto.LaneResult;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.port.in.SearchRouteUseCase;
import watoo.grd.nextroute.application.route.service.TransferStopResolver;
import watoo.grd.nextroute.application.route.service.TransferStopResolver.RouteResolution;
import watoo.grd.nextroute.application.route.service.TransferStopResolver.SeqResolution;
import watoo.grd.nextroute.application.route.service.TransferStopResolver.StopResolution;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertReliability;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link RouteTravelTimeEstimator} 기본 구현: 경로검색(ODsay+TMAP+보강) 재사용.
 * STATIC = searchWithoutRealtime + 버스 대기 term/2, REALTIME = search + enricher waitSeconds.
 * 후보 선택: AVAILABLE 최소 → UNKNOWN 최소 → 전부 OUT_OF_SERVICE 면 예외.
 */
@Component
@RequiredArgsConstructor
public class RouteSearchTravelTimeEstimator implements RouteTravelTimeEstimator {

    private static final int TRAFFIC_SUBWAY = 1;
    private static final int TRAFFIC_BUS = 2;
    private static final int TRAFFIC_WALK = 3;

    private final SearchRouteUseCase searchRouteUseCase;
    private final TransferStopResolver resolver;
    private final RouteServiceWindowChecker serviceWindowChecker;
    private final RouteArrivalAlertProperties properties;

    @Override
    public RouteEvaluation evaluate(TravelTimeQuery q, EvaluationMode mode) {
        RouteSearchRequest req = new RouteSearchRequest(
                q.startX(), q.startY(), q.endX(), q.endY(), q.startName(), q.endName());
        RouteSearchResult result = mode == EvaluationMode.STATIC
                ? searchRouteUseCase.searchWithoutRealtime(req)
                : searchRouteUseCase.search(req);

        if (result.paths() == null || result.paths().isEmpty()) {
            throw new IllegalStateException("경로를 찾을 수 없습니다.");
        }

        List<PathEval> evals = new ArrayList<>();
        for (PathResult path : result.paths()) {
            evals.add(evaluatePath(path, mode, q.targetArrivalAt(), q.bufferMinutes()));
        }

        // 후보 선택: AVAILABLE 최소 → UNKNOWN 최소 → 전부 OUT_OF_SERVICE 면 예외.
        PathEval chosen = pick(evals, RouteServiceWindowChecker.Availability.AVAILABLE);
        if (chosen == null) chosen = pick(evals, RouteServiceWindowChecker.Availability.UNKNOWN);
        if (chosen == null) {
            throw new RouteOutOfServiceException("모든 후보 경로가 운행시간 밖입니다.");
        }

        return new RouteEvaluation(
                chosen.durationSec,
                chosen.reliability,
                new SelectedPath(chosen.fixedSec, chosen.boardings, chosen.eligibility),
                chosen.maxHeadwayMinutes);
    }

    private PathEval pick(List<PathEval> evals, RouteServiceWindowChecker.Availability avail) {
        return evals.stream()
                .filter(e -> e.availability == avail)
                .min((a, b) -> Integer.compare(a.durationSec, b.durationSec))
                .orElse(null);
    }

    private PathEval evaluatePath(PathResult path, EvaluationMode mode,
                                  LocalDateTime targetArrivalAt, int bufferMinutes) {
        int fixedSec = 0;       // 대기 제외 고정 소요 누적
        int waitSec = 0;        // 버스 대기 누적
        Integer maxHeadway = null;
        boolean anyApprox = false;
        boolean anyFallback = false;
        boolean anyUnresolved = false;
        List<Boarding> boardings = new ArrayList<>();
        List<BusSeg> busSegs = new ArrayList<>();

        List<SubPathResult> subs = path.subPaths() != null ? path.subPaths() : List.of();
        for (SubPathResult sp : subs) {
            int traffic = sp.trafficType();
            if (traffic == TRAFFIC_WALK) {
                fixedSec += sp.walkTotalTimeSeconds() != null
                        ? sp.walkTotalTimeSeconds()
                        : sp.sectionTime() * 60;
            } else if (traffic == TRAFFIC_SUBWAY) {
                fixedSec += sp.sectionTime() * 60;
                anyApprox = true;   // 지하철 대기 미반영 → 근사
            } else if (traffic == TRAFFIC_BUS) {
                BusLaneChoice choice = chooseBusLane(sp, mode);
                int cumulativeBefore = fixedSec + waitSec;   // 정류장 도착까지 누적(fixed+wait)
                int offsetSec = fixedSec;                    // 스냅샷 offsetSec = 고정 소요만

                waitSec += choice.waitSec;
                // 승차 후 ride 는 고정. 고른 lane 의 탑승시간을 알면 그 값으로(묶인 노선마다 다르다, bus-lane-selection PR-1)
                fixedSec += choice.rideSec != null ? choice.rideSec : sp.sectionTime() * 60;

                if (choice.maxTerm != null) {
                    maxHeadway = maxHeadway == null ? choice.maxTerm : Math.max(maxHeadway, choice.maxTerm);
                }
                if (choice.approx) anyApprox = true;
                if (choice.fallback) anyFallback = true;
                if (!choice.resolved) {
                    anyUnresolved = true;
                } else {
                    int headway = choice.maxTerm != null ? choice.maxTerm : properties.getDefaultHeadwayMinutes();
                    boardings.add(new Boarding(choice.stopId, choice.routeId, choice.seq, offsetSec, headway));
                    busSegs.add(new BusSeg(choice.stopId, choice.routeId, cumulativeBefore));
                }
            }
        }

        int durationSec = fixedSec + waitSec;

        // 서비스윈도: 예상 승차시각 = leaveBy(= target − duration − buffer) + cumulativeBefore.
        RouteServiceWindowChecker.Availability availability = RouteServiceWindowChecker.Availability.AVAILABLE;
        if (targetArrivalAt != null) {
            LocalDateTime leaveByEstimate = targetArrivalAt.minusSeconds(durationSec).minusMinutes(bufferMinutes);
            for (BusSeg seg : busSegs) {
                LocalDateTime boardingAt = leaveByEstimate.plusSeconds(seg.cumulativeBeforeSec);
                RouteServiceWindowChecker.Availability a =
                        serviceWindowChecker.judge(seg.routeId, seg.stopId, boardingAt);
                availability = worst(availability, a);
            }
        }

        boolean hasBus = !busSegs.isEmpty() || anyUnresolved;
        FinalCheckEligibility eligibility = !hasBus
                ? FinalCheckEligibility.NO_BUS
                : (anyUnresolved ? FinalCheckEligibility.UNRESOLVED_BUS : FinalCheckEligibility.ELIGIBLE);

        RouteAlertReliability reliability = reliabilityOf(mode, hasBus, anyApprox, anyFallback, anyUnresolved);

        return new PathEval(durationSec, fixedSec, boardings, maxHeadway,
                availability, eligibility, reliability);
    }

    /** 버스 subPath 의 lane 중 최적 선택. STATIC=term 최소, REALTIME=waitSeconds 최소(없으면 term/2 fallback). */
    private BusLaneChoice chooseBusLane(SubPathResult sp, EvaluationMode mode) {
        Optional<StopResolution> stopRes = resolver.resolveStop(sp.startLocalStationID());
        String stopId = stopRes.map(StopResolution::stopId).orElse(null);

        List<LaneResult> lanes = sp.lanes() != null ? sp.lanes() : List.of();
        List<TransferArrival> arrivals = sp.transferArrivals() != null ? sp.transferArrivals() : List.of();

        // 실시간/모델 후보와 term/2 fallback 후보를 분리 집계. 실시간이 있으면 fallback 보다 항상 우선
        // (fallback 대기가 우연히 짧아도 실제 도착을 가림 방지).
        BusLaneChoice realtimeBest = null;
        BusLaneChoice fallbackBest = null;
        for (int li = 0; li < lanes.size(); li++) {
            LaneResult lane = lanes.get(li);
            Optional<RouteResolution> routeRes = resolver.resolveRoute(lane.busLocalBlID(), lane.busNo());
            if (routeRes.isEmpty() || stopId == null) continue;   // resolve 실패 lane 은 건너뜀
            RouteResolution rr = routeRes.get();
            SeqResolution seqRes = resolver.resolveSeq(rr.routeId(), stopId);
            Integer seq = seqRes.candidates().size() == 1 ? seqRes.candidates().get(0) : null;
            if (seq == null) continue;

            int headway = rr.termMinutes() != null ? rr.termMinutes() : properties.getDefaultHeadwayMinutes();
            int staticWait = headway * 60 / 2;

            if (mode == EvaluationMode.STATIC) {
                // STATIC 은 term/2 가 정식 추정(fallback 아님) → realtimeBest 슬롯에 최소 대기로 집계.
                BusLaneChoice cand = new BusLaneChoice(true, false, false, stopId, rr.routeId(), seq, staticWait, headway);
                cand.rideSec = positive(lane.rideSeconds());
                if (realtimeBest == null || cand.waitSec < realtimeBest.waitSec) realtimeBest = cand;
            } else {
                TransferArrival ta = arrivalForLane(arrivals, li);
                boolean valid = ta != null && ta.waitSeconds() != null
                        && (ta.status() == TransferArrival.Status.AVAILABLE
                            || ta.status() == TransferArrival.Status.ARRIVAL_TIME_APPROXIMATE);
                if (valid) {
                    int wait = (int) Math.max(0, ta.waitSeconds());
                    boolean approx = ta.status() == TransferArrival.Status.ARRIVAL_TIME_APPROXIMATE;
                    BusLaneChoice cand = new BusLaneChoice(true, false, approx, stopId, rr.routeId(), seq, wait, headway);
                    cand.rideSec = positive(lane.rideSeconds());
                    if (realtimeBest == null || wait < realtimeBest.waitSec) realtimeBest = cand;
                } else {
                    BusLaneChoice fb = new BusLaneChoice(true, true, false, stopId, rr.routeId(), seq, staticWait, headway);
                    fb.rideSec = positive(lane.rideSeconds());
                    if (fallbackBest == null || fb.waitSec < fallbackBest.waitSec) fallbackBest = fb;
                }
            }
        }

        BusLaneChoice best = realtimeBest != null ? realtimeBest : fallbackBest;
        if (best == null) {
            // resolve 전멸 → 대기 근사 불가, 고정 headway 로만. boarding 기록 불가(unresolved).
            int headway = properties.getDefaultHeadwayMinutes();
            return new BusLaneChoice(false, true, false, stopId, null, null, headway * 60 / 2, headway);
        }
        return best;
    }

    private TransferArrival arrivalForLane(List<TransferArrival> arrivals, int laneIdx) {
        return arrivals.stream().filter(a -> a.laneIndex() == laneIdx).findFirst().orElse(null);
    }

    private RouteAlertReliability reliabilityOf(EvaluationMode mode, boolean hasBus,
                                                boolean anyApprox, boolean anyFallback, boolean anyUnresolved) {
        if (anyUnresolved || anyFallback) return RouteAlertReliability.LOW;
        if (mode == EvaluationMode.STATIC) {
            // baseline 은 버스 대기가 term/2 추정 → 버스 있으면 MEDIUM, 없으면(지하철 근사 포함) MEDIUM/HIGH
            if (hasBus || anyApprox) return RouteAlertReliability.MEDIUM;
            return RouteAlertReliability.HIGH;
        }
        if (anyApprox) return RouteAlertReliability.MEDIUM;
        return RouteAlertReliability.HIGH;
    }

    private RouteServiceWindowChecker.Availability worst(
            RouteServiceWindowChecker.Availability a, RouteServiceWindowChecker.Availability b) {
        if (a == RouteServiceWindowChecker.Availability.OUT_OF_SERVICE
                || b == RouteServiceWindowChecker.Availability.OUT_OF_SERVICE) {
            return RouteServiceWindowChecker.Availability.OUT_OF_SERVICE;
        }
        if (a == RouteServiceWindowChecker.Availability.UNKNOWN
                || b == RouteServiceWindowChecker.Availability.UNKNOWN) {
            return RouteServiceWindowChecker.Availability.UNKNOWN;
        }
        return RouteServiceWindowChecker.Availability.AVAILABLE;
    }

    // ── 내부 홀더 ────────────────────────────────────────────────────────────

    private static final class PathEval {
        final int durationSec;
        final int fixedSec;
        final List<Boarding> boardings;
        final Integer maxHeadwayMinutes;
        final RouteServiceWindowChecker.Availability availability;
        final FinalCheckEligibility eligibility;
        final RouteAlertReliability reliability;

        PathEval(int durationSec, int fixedSec, List<Boarding> boardings, Integer maxHeadwayMinutes,
                 RouteServiceWindowChecker.Availability availability,
                 FinalCheckEligibility eligibility, RouteAlertReliability reliability) {
            this.durationSec = durationSec;
            this.fixedSec = fixedSec;
            this.boardings = boardings;
            this.maxHeadwayMinutes = maxHeadwayMinutes;
            this.availability = availability;
            this.eligibility = eligibility;
            this.reliability = reliability;
        }
    }

    private static Integer positive(Integer seconds) {
        return seconds != null && seconds > 0 ? seconds : null;
    }

    private static final class BusLaneChoice {
        /** 고른 lane 의 탑승시간(초). 모르면 null → 구간 값. */
        Integer rideSec;
        final boolean resolved;
        final boolean fallback;
        final boolean approx;
        final String stopId;
        final String routeId;
        final Integer seq;
        final int waitSec;
        final Integer maxTerm;

        BusLaneChoice(boolean resolved, boolean fallback, boolean approx,
                      String stopId, String routeId, Integer seq, int waitSec, Integer maxTerm) {
            this.resolved = resolved;
            this.fallback = fallback;
            this.approx = approx;
            this.stopId = stopId;
            this.routeId = routeId;
            this.seq = seq;
            this.waitSec = waitSec;
            this.maxTerm = maxTerm;
        }
    }

    private static final class BusSeg {
        final String stopId;
        final String routeId;
        final int cumulativeBeforeSec;

        BusSeg(String stopId, String routeId, int cumulativeBeforeSec) {
            this.stopId = stopId;
            this.routeId = routeId;
            this.cumulativeBeforeSec = cumulativeBeforeSec;
        }
    }
}
