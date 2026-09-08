package watoo.grd.nextroute.application.route.timeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-E ETA 합성 계약. 하나의 시간축으로 WAIT/RIDE 분리, 실시간 대체(REALTIME_CAPABLE·horizon·freshness),
 * 취소≠stale, effective total = 최종도착-요청출발(중복 buffer 금지). Clock 고정.
 */
class RouteTimelineComposerTest {

    private final Instant reqDep = Instant.parse("2026-09-08T01:00:00Z"); // KST 10:00
    private final Clock clock = Clock.fixed(reqDep, ZoneOffset.UTC);
    private final TransferArrivalProperties ta = new TransferArrivalProperties(); // buffer 60, stale 90
    private final TransferPredictProperties tp = new TransferPredictProperties(); // horizon 40min
    private final RouteTimelineComposer composer =
            new RouteTimelineComposer(clock, ta, tp, new ConnectionFeasibilityEvaluator());

    private final RealtimeLegTimingLookup noRealtime = (leg, stop, board) -> Optional.empty();

    private Instant t(String iso) { return Instant.parse(iso); }

    private StopOccurrence stop() { return new StopOccurrence("1:S", null, null, 0, 127.0, 37.5, "s"); }

    private RoutingLeg transit(RoutingMode mode, Instant board, Instant alight, RealtimeCoverage cov) {
        int ride = (int) Duration.between(board, alight).getSeconds();
        return new RoutingLeg(mode, null, "1", "A1", "R", "route", "trip", null,
                board, alight, ride, List.of(stop()), List.of(), null,
                Enrichment.staticOnly(cov), null);
    }

    private RoutingLeg walk(Instant board, Instant alight) {
        return new RoutingLeg(RoutingMode.WALK, null, "1", null, null, null, null, null,
                board, alight, 0, List.of(), List.of(), null, null, null);
    }

    private RoutingItinerary itin(Instant staticArrival, RoutingLeg... legs) {
        return new RoutingItinerary(staticArrival, 0, 0, 0, 0, true, List.of(), null, List.of(legs));
    }

    private TimelineSegment seg(ComposedTimeline t, int i) { return t.segments().get(i); }

    @Test
    @DisplayName("정적 단일편: WAIT/RIDE 분리, effective total=도착-출발, buffer 중복없음")
    void staticSplit() {
        ComposedTimeline r = composer.compose(
                itin(t("2026-09-08T01:25:00Z"),
                        transit(RoutingMode.BUS, t("2026-09-08T01:05:00Z"), t("2026-09-08T01:25:00Z"),
                                RealtimeCoverage.STATIC_SCHEDULE)),
                reqDep, noRealtime);

        assertThat(r.feasible()).isTrue();
        assertThat(r.feasibility()).isEqualTo(Feasibility.FEASIBLE);
        assertThat(r.segments()).hasSize(2);
        assertThat(seg(r, 0).type()).isEqualTo(SegmentType.WAIT);
        assertThat(seg(r, 0).seconds()).isEqualTo(300);
        assertThat(seg(r, 0).source()).isEqualTo(ActualSource.STATIC);
        assertThat(seg(r, 1).type()).isEqualTo(SegmentType.RIDE);
        assertThat(seg(r, 1).seconds()).isEqualTo(1200);
        assertThat(r.effectiveArrivalAt()).isEqualTo(t("2026-09-08T01:25:00Z"));
        assertThat(r.staticArrivalAt()).isEqualTo(t("2026-09-08T01:25:00Z"));
        assertThat(r.initialWaitSeconds()).isEqualTo(300);
        // buffer(60) 를 총시간에 다시 더하지 않는다: 정확히 1500초.
        assertThat(r.effectiveTotalSeconds()).isEqualTo(1500);
        assertThat(r.effectiveTotalMinutes()).isEqualTo(25);
    }

    @Test
    @DisplayName("0초 대기: board==userReadyAt → WAIT 0초, feasible")
    void zeroWait() {
        ComposedTimeline r = composer.compose(
                itin(t("2026-09-08T01:20:00Z"),
                        transit(RoutingMode.BUS, reqDep, t("2026-09-08T01:20:00Z"),
                                RealtimeCoverage.STATIC_SCHEDULE)),
                reqDep, noRealtime);
        assertThat(r.feasible()).isTrue();
        assertThat(r.initialWaitSeconds()).isZero();
        assertThat(seg(r, 0).type()).isEqualTo(SegmentType.WAIT);
        assertThat(seg(r, 0).seconds()).isZero();
    }

    @Test
    @DisplayName("버스→지하철→버스: 환승도보 누적, cursor=alightAt 체인, 최종도착")
    void busSubwayBus() {
        ComposedTimeline r = composer.compose(
                itin(t("2026-09-08T01:45:00Z"),
                        transit(RoutingMode.BUS, reqDep, t("2026-09-08T01:10:00Z"), RealtimeCoverage.STATIC_SCHEDULE),
                        walk(t("2026-09-08T01:10:00Z"), t("2026-09-08T01:12:00Z")),
                        transit(RoutingMode.SUBWAY, t("2026-09-08T01:15:00Z"), t("2026-09-08T01:30:00Z"), RealtimeCoverage.STATIC_SCHEDULE),
                        walk(t("2026-09-08T01:30:00Z"), t("2026-09-08T01:31:00Z")),
                        transit(RoutingMode.BUS, t("2026-09-08T01:35:00Z"), t("2026-09-08T01:45:00Z"), RealtimeCoverage.STATIC_SCHEDULE)),
                reqDep, noRealtime);
        assertThat(r.feasible()).isTrue();
        assertThat(r.effectiveArrivalAt()).isEqualTo(t("2026-09-08T01:45:00Z"));
        // WAIT,RIDE,WALK,WAIT,RIDE,WALK,WAIT,RIDE = 8 세그먼트
        assertThat(r.segments()).extracting(TimelineSegment::type).containsExactly(
                SegmentType.WAIT, SegmentType.RIDE, SegmentType.WALK,
                SegmentType.WAIT, SegmentType.RIDE, SegmentType.WALK,
                SegmentType.WAIT, SegmentType.RIDE);
    }

    @Test
    @DisplayName("실시간 도착지연(10:05→10:12) → 다음편(10:10) 놓침 MISSED, 재검색 앵커")
    void realtimeDelayCascadeMissed() {
        RoutingLeg sub = transit(RoutingMode.SUBWAY, reqDep, t("2026-09-08T01:05:00Z"), RealtimeCoverage.REALTIME_CAPABLE);
        RealtimeLegTimingLookup rt = (leg, stop, board) -> leg == sub
                ? Optional.of(new RealtimeLegTiming(reqDep, t("2026-09-08T01:12:00Z"), false, ActualSource.REALTIME, reqDep))
                : Optional.empty();
        ComposedTimeline r = composer.compose(
                itin(t("2026-09-08T01:20:00Z"),
                        sub,
                        walk(t("2026-09-08T01:05:00Z"), t("2026-09-08T01:06:00Z")),
                        transit(RoutingMode.BUS, t("2026-09-08T01:10:00Z"), t("2026-09-08T01:20:00Z"), RealtimeCoverage.STATIC_SCHEDULE)),
                reqDep, rt);
        assertThat(r.feasible()).isFalse();
        assertThat(r.feasibility()).isEqualTo(Feasibility.MISSED);
        assertThat(r.reFromLegIndex()).isEqualTo(2);
        assertThat(r.reReadyAt()).isEqualTo(t("2026-09-08T01:13:00Z"));
        assertThat(r.exclusionReason()).isNotBlank();
    }

    @Test
    @DisplayName("stale 실시간 → 무시하고 정적 fallback, feasible(STATIC)")
    void staleFallback() {
        RoutingLeg sub = transit(RoutingMode.SUBWAY, reqDep, t("2026-09-08T01:05:00Z"), RealtimeCoverage.REALTIME_CAPABLE);
        RealtimeLegTimingLookup rt = (leg, stop, board) -> leg == sub
                ? Optional.of(new RealtimeLegTiming(reqDep, t("2026-09-08T01:12:00Z"), false, ActualSource.REALTIME,
                        reqDep.minusSeconds(200))) // observedAt 200s 과거 > stale 90
                : Optional.empty();
        ComposedTimeline r = composer.compose(
                itin(t("2026-09-08T01:20:00Z"),
                        sub,
                        walk(t("2026-09-08T01:05:00Z"), t("2026-09-08T01:06:00Z")),
                        transit(RoutingMode.BUS, t("2026-09-08T01:10:00Z"), t("2026-09-08T01:20:00Z"), RealtimeCoverage.STATIC_SCHEDULE)),
                reqDep, rt);
        assertThat(r.feasible()).isTrue();
        assertThat(r.effectiveArrivalAt()).isEqualTo(t("2026-09-08T01:20:00Z"));
        assertThat(seg(r, 1).source()).isEqualTo(ActualSource.STATIC); // 첫 RIDE = 정적
    }

    @Test
    @DisplayName("운행취소 → CANCELLED, 정적 fallback 금지(후보 제외)")
    void cancelled() {
        RoutingLeg bus = transit(RoutingMode.BUS, t("2026-09-08T01:05:00Z"), t("2026-09-08T01:20:00Z"),
                RealtimeCoverage.REALTIME_CAPABLE);
        RealtimeLegTimingLookup rt = (leg, stop, board) ->
                Optional.of(new RealtimeLegTiming(null, null, true, ActualSource.REALTIME, reqDep));
        ComposedTimeline r = composer.compose(itin(t("2026-09-08T01:20:00Z"), bus), reqDep, rt);
        assertThat(r.feasibility()).isEqualTo(Feasibility.CANCELLED);
        assertThat(r.feasible()).isFalse();
        assertThat(r.reFromLegIndex()).isZero();
    }

    @Test
    @DisplayName("환승편 실시간 조기출발(도착+buffer 전 출발) → MISSED (첫 탑승엔 buffer 미적용)")
    void realtimeEarlierDepartureMissedOnTransfer() {
        // 환승: leg0 승차 → 도보 도착 01:14 → leg2 계획 01:15 인데 실시간 01:14:30 조기출발 → 못 탐
        RoutingLeg leg2 = transit(RoutingMode.BUS, t("2026-09-08T01:15:00Z"), t("2026-09-08T01:25:00Z"),
                RealtimeCoverage.REALTIME_CAPABLE);
        RealtimeLegTimingLookup rt = (leg, stop, board) -> leg == leg2
                ? Optional.of(new RealtimeLegTiming(t("2026-09-08T01:14:30Z"), t("2026-09-08T01:24:00Z"),
                        false, ActualSource.REALTIME, reqDep))
                : Optional.empty();
        ComposedTimeline r = composer.compose(
                itin(t("2026-09-08T01:25:00Z"),
                        transit(RoutingMode.BUS, reqDep, t("2026-09-08T01:10:00Z"), RealtimeCoverage.STATIC_SCHEDULE),
                        walk(t("2026-09-08T01:10:00Z"), t("2026-09-08T01:14:00Z")),
                        leg2),
                reqDep, rt);
        assertThat(r.feasibility()).isEqualTo(Feasibility.MISSED);
        assertThat(r.reFromLegIndex()).isEqualTo(2);
        assertThat(r.reReadyAt()).isEqualTo(t("2026-09-08T01:14:00Z"));
    }

    @Test
    @DisplayName("절대시각 없는 대중교통 leg(ODsay shape) → NO_CATCHABLE (NPE 아님)")
    void transitLegWithoutAbsoluteTime() {
        RoutingLeg noTime = new RoutingLeg(RoutingMode.BUS, null, "1", "A1", "R", "route", "trip", null,
                null, null, 0, List.of(stop()), List.of(), null,
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), null);
        ComposedTimeline r = composer.compose(itin(null, noTime), reqDep, noRealtime);
        assertThat(r.feasible()).isFalse();
        assertThat(r.feasibility()).isEqualTo(Feasibility.NO_CATCHABLE);
        assertThat(r.reFromLegIndex()).isZero();
        assertThat(r.exclusionReason()).isNotBlank();
    }

    @Test
    @DisplayName("MODEL_CAPABLE leg 도 실시간(ML) 대체 대상 → source=MODEL")
    void modelCapableApplied() {
        RoutingLeg bus = transit(RoutingMode.BUS, reqDep, t("2026-09-08T01:20:00Z"),
                RealtimeCoverage.MODEL_CAPABLE);
        RealtimeLegTimingLookup rt = (leg, stop, board) -> Optional.of(new RealtimeLegTiming(
                reqDep, t("2026-09-08T01:22:00Z"), false, ActualSource.MODEL, reqDep));
        ComposedTimeline r = composer.compose(itin(t("2026-09-08T01:20:00Z"), bus), reqDep, rt);
        assertThat(r.feasible()).isTrue();
        assertThat(seg(r, 1).type()).isEqualTo(SegmentType.RIDE);
        assertThat(seg(r, 1).source()).isEqualTo(ActualSource.MODEL);
        assertThat(r.effectiveArrivalAt()).isEqualTo(t("2026-09-08T01:22:00Z"));
    }

    @Test
    @DisplayName("실시간 도착<출발(모순) → 무시하고 정적 fallback(cursor 역행 금지)")
    void realtimeArrivalBeforeDepartureFallsBackToStatic() {
        RoutingLeg bus = transit(RoutingMode.BUS, reqDep, t("2026-09-08T01:20:00Z"),
                RealtimeCoverage.REALTIME_CAPABLE);
        RealtimeLegTimingLookup rt = (leg, stop, board) -> Optional.of(new RealtimeLegTiming(
                t("2026-09-08T01:06:00Z"), t("2026-09-08T01:04:00Z"), false, ActualSource.REALTIME, reqDep));
        ComposedTimeline r = composer.compose(itin(t("2026-09-08T01:20:00Z"), bus), reqDep, rt);
        assertThat(r.feasible()).isTrue();
        assertThat(seg(r, 1).source()).isEqualTo(ActualSource.STATIC);
        assertThat(r.effectiveArrivalAt()).isEqualTo(t("2026-09-08T01:20:00Z"));
    }

    @Test
    @DisplayName("자정 넘김(25시): 절대시각이라 총시간 정상")
    void crossMidnight() {
        Instant dep = t("2026-09-08T14:50:00Z"); // KST 23:50
        Clock c = Clock.fixed(dep, ZoneOffset.UTC);
        RouteTimelineComposer comp = new RouteTimelineComposer(c, ta, tp, new ConnectionFeasibilityEvaluator());
        ComposedTimeline r = comp.compose(
                itin(t("2026-09-08T15:10:00Z"), // KST 00:10 다음날
                        transit(RoutingMode.SUBWAY, t("2026-09-08T14:55:00Z"), t("2026-09-08T15:10:00Z"),
                                RealtimeCoverage.STATIC_SCHEDULE)),
                dep, noRealtime);
        assertThat(r.feasible()).isTrue();
        assertThat(r.effectiveTotalSeconds()).isEqualTo(1200); // 23:50 → 00:10 = 20분
        assertThat(r.effectiveTotalMinutes()).isEqualTo(20);
    }

    @Test
    @DisplayName("horizon 밖 미래 출발엔 실시간 미적용 → 정적")
    void outOfHorizonKeepsStatic() {
        // 출발편 계획 02:00 = now(01:00)+60min > horizon 40min
        RoutingLeg bus = transit(RoutingMode.BUS, t("2026-09-08T02:00:00Z"), t("2026-09-08T02:20:00Z"),
                RealtimeCoverage.REALTIME_CAPABLE);
        RealtimeLegTimingLookup rt = (leg, stop, board) -> Optional.of(new RealtimeLegTiming(
                t("2026-09-08T02:00:00Z"), t("2026-09-08T02:30:00Z"), false, ActualSource.REALTIME, reqDep));
        ComposedTimeline r = composer.compose(itin(t("2026-09-08T02:20:00Z"), bus), reqDep, rt);
        assertThat(r.feasible()).isTrue();
        assertThat(r.effectiveArrivalAt()).isEqualTo(t("2026-09-08T02:20:00Z")); // 실시간 02:30 무시
        assertThat(seg(r, 1).source()).isEqualTo(ActualSource.STATIC);
    }
}
