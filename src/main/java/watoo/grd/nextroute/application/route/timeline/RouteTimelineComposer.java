package watoo.grd.nextroute.application.route.timeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.config.TransferPredictProperties;
import watoo.grd.nextroute.application.route.routing.ActualSource;
import watoo.grd.nextroute.application.route.routing.Enrichment;
import watoo.grd.nextroute.application.route.routing.RealtimeCoverage;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 하나의 시간축으로 ETA 를 합성한다(P2-E). 요청출발 → (접근도보) → userReadyAt → 편 선택 →
 * WAIT/RIDE 분리 → 하차/환승도보 누적 → 다음 편 재평가. 검증된 실시간 지원 leg 만 실시간으로 대체하고,
 * horizon/freshness 밖이거나 미지원이면 정적을 쓴다. 운행취소는 정적 fallback 하지 않고 후보를 제외한다.
 *
 * <p><b>대기 중복 금지</b>: waitSeconds = boardAt - userReadyAt 로 buffer 를 이미 포함한다.
 * effective total = 최종도착 - 요청출발 로만 계산하고 실시간 대기를 다시 더하지 않는다(분은 마지막 반올림).
 *
 * <p><b>buffer 적용 범위(근사 정책)</b>: 첫 탑승은 사용자가 정류장에서 대기 중이라 buffer 를 적용하지 않는다
 * (출발이 준비 이전이면 MISSED). 환승편은 실시간/드리프트로 시각이 바뀐 경우에만
 * "출발 ≥ userReadyAt + buffer" 를 요구한다. 순수 정적(드리프트 없음) 환승은 OTP 가 이미 환승여유를
 * 계획했으므로 그대로 신뢰한다.
 */
@Slf4j
@Component
public class RouteTimelineComposer {

    private final Clock clock;
    private final TransferArrivalProperties ta;
    private final TransferPredictProperties tp;
    private final ConnectionFeasibilityEvaluator evaluator;

    public RouteTimelineComposer(Clock clock, TransferArrivalProperties ta,
                                 TransferPredictProperties tp, ConnectionFeasibilityEvaluator evaluator) {
        this.clock = clock;
        this.ta = ta;
        this.tp = tp;
        this.evaluator = evaluator;
    }

    public ComposedTimeline compose(RoutingItinerary itin, Instant requestedDepartureAt,
                                    RealtimeLegTimingLookup realtime) {
        Instant now = clock.instant();
        long buffer = ta.getBoardingBufferSeconds();
        long staleAfter = ta.getRealtimeStaleAfterSeconds();
        long horizonSec = tp.getMaxFutureMinutes() * 60;

        List<TimelineSegment> segments = new ArrayList<>();
        Instant cursor = requestedDepartureAt;
        boolean drifted = false;
        boolean boardedOnce = false;
        Integer initialWait = null;

        Feasibility feasibility = Feasibility.FEASIBLE;
        String exclusion = null;
        Instant reReadyAt = null;
        int reFromLeg = -1;

        List<RoutingLeg> legs = itin.legs();
        for (int i = 0; i < legs.size(); i++) {
            RoutingLeg leg = legs.get(i);

            if (leg.mode() == RoutingMode.WALK) {
                long ws = durationSeconds(leg.boardAt(), leg.alightAt());
                Instant end = cursor.plusSeconds(ws);
                segments.add(new TimelineSegment(SegmentType.WALK, cursor, end, (int) ws, ActualSource.STATIC, i));
                cursor = end;
                continue;
            }

            Instant userReadyAt = cursor;
            Instant plannedBoard = leg.boardAt();
            int ride = leg.rideDurationSeconds();

            // 이 composer 는 절대시각(OTP planConnection) leg 을 전제한다. 상대 소요만 있는 provider shape
            // (ODsay 는 boardAt/alightAt null)은 하나의 시간축에 놓을 수 없다 → cursor 를 null 로 오염시키지 말고
            // 명시적으로 후보 제외한다(조용한 0/ NPE 금지).
            if (plannedBoard == null) {
                feasibility = Feasibility.NO_CATCHABLE;
                exclusion = "leg " + i + " 절대 승차시각 없음 (ETA composer 미지원 provider shape)";
                reReadyAt = null;
                reFromLeg = i;
                break;
            }
            Instant plannedAlight = plannedBoard.plusSeconds(ride);

            Instant effBoard = plannedBoard;
            Instant effAlight = plannedAlight;
            ActualSource source = ActualSource.STATIC;
            boolean cancelled = false;
            boolean rtApplied = false;

            if (isEnrichmentCapable(leg)) {
                Optional<RealtimeLegTiming> opt = realtime.timingFor(leg, boardingStop(leg), plannedBoard);
                if (opt.isPresent()) {
                    RealtimeLegTiming rt = opt.get();
                    if (rt.cancelled()) {
                        cancelled = true;
                    } else if (rt.realtimeDepartureAt() != null
                            && withinHorizon(plannedBoard, now, horizonSec)
                            && fresh(rt.observedAt(), now, staleAfter)) {
                        Instant rtBoard = rt.realtimeDepartureAt();
                        Instant rtAlight = rt.realtimeArrivalAt() != null
                                ? rt.realtimeArrivalAt() : rtBoard.plusSeconds(ride);
                        if (rtAlight.isBefore(rtBoard)) {
                            // 도착<출발 모순 실시간 → 신뢰 불가. 정적 유지(cursor 역행 금지).
                            log.warn("ETA compose: leg {} realtime arrival {} < departure {} — static fallback",
                                    i, rtAlight, rtBoard);
                        } else {
                            effBoard = rtBoard;
                            effAlight = rtAlight;
                            source = rt.source() != null ? rt.source() : ActualSource.REALTIME;
                            rtApplied = true;
                        }
                    }
                }
            }

            Feasibility f = feasibilityOf(cancelled, boardedOnce, drifted, rtApplied,
                    userReadyAt, effBoard, plannedBoard, buffer);
            if (f != Feasibility.FEASIBLE) {
                feasibility = f;
                exclusion = (f == Feasibility.CANCELLED ? "leg " + i + " cancelled" : "missed connection at leg " + i);
                reReadyAt = userReadyAt;
                reFromLeg = i;
                break;
            }

            Instant boardForSeg = effBoard != null ? effBoard : userReadyAt;
            long waitSec = Math.max(0, durationSeconds(userReadyAt, boardForSeg));
            segments.add(new TimelineSegment(SegmentType.WAIT, userReadyAt, boardForSeg, (int) waitSec, source, i));
            if (initialWait == null) {
                initialWait = (int) waitSec;
            }
            long rideSec = Math.max(0, durationSeconds(boardForSeg, effAlight));
            segments.add(new TimelineSegment(SegmentType.RIDE, boardForSeg, effAlight, (int) rideSec, source, i));

            if (rtApplied && !effAlight.equals(plannedAlight)) {
                drifted = true;
            }
            boardedOnce = true;
            cursor = effAlight;
        }

        boolean feasible = feasibility == Feasibility.FEASIBLE;
        Instant effArrival = feasible ? cursor : null;
        int totalSec = feasible ? (int) durationSeconds(requestedDepartureAt, cursor) : -1;
        int totalMin = feasible ? (int) Math.round(totalSec / 60.0) : -1;

        return new ComposedTimeline(requestedDepartureAt, effArrival, itin.staticArrivalAt(),
                totalSec, totalMin, initialWait == null ? 0 : initialWait,
                feasible, feasibility, exclusion, segments, reReadyAt, reFromLeg);
    }

    /**
     * 첫 탑승은 정류장 대기 중 → buffer 없이 "출발이 준비 이전이면 MISSED". 환승편은 실시간/드리프트가
     * 시각을 바꾼 경우에만 buffer 를 요구한다(순수 정적 환승은 OTP 계획 신뢰).
     */
    private Feasibility feasibilityOf(boolean cancelled, boolean boardedOnce, boolean drifted,
                                      boolean rtApplied, Instant userReadyAt, Instant effBoard,
                                      Instant plannedBoard, long buffer) {
        if (cancelled) {
            return Feasibility.CANCELLED;
        }
        if (!boardedOnce) {
            // 첫 탑승: 대기 중이므로 buffer 미적용. 출발이 준비 이전(이미 지나감)이면 못 탐.
            return effBoard != null && effBoard.isBefore(userReadyAt) ? Feasibility.MISSED : Feasibility.FEASIBLE;
        }
        // 환승편
        boolean shifted = rtApplied && effBoard != null && plannedBoard != null && !effBoard.equals(plannedBoard);
        if (!drifted && !shifted) {
            return Feasibility.FEASIBLE; // 정적 환승 = OTP 계획 신뢰
        }
        return evaluator.evaluate(userReadyAt, effBoard, buffer, false);
    }

    /** 실시간 도착(REALTIME_CAPABLE) 또는 ML 승차예측(MODEL_CAPABLE) 지원 leg 만 대체 대상(PLAN P2-E "실시간 도착/ML"). */
    private static boolean isEnrichmentCapable(RoutingLeg leg) {
        Enrichment e = leg.enrichment();
        return e != null && (e.coverage() == RealtimeCoverage.REALTIME_CAPABLE
                || e.coverage() == RealtimeCoverage.MODEL_CAPABLE);
    }

    private static StopOccurrence boardingStop(RoutingLeg leg) {
        return leg.stops().isEmpty() ? null : leg.stops().get(0);
    }

    private static boolean withinHorizon(Instant plannedBoard, Instant now, long horizonSec) {
        return !plannedBoard.isAfter(now.plusSeconds(horizonSec));
    }

    private static boolean fresh(Instant observedAt, Instant now, long staleAfter) {
        return observedAt != null && !observedAt.isBefore(now.minusSeconds(staleAfter));
    }

    private static long durationSeconds(Instant a, Instant b) {
        if (a == null || b == null) {
            return 0;
        }
        return Duration.between(a, b).getSeconds();
    }
}
