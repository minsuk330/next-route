package watoo.grd.nextroute.application.route.timeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.config.TransferPredictProperties;
import watoo.grd.nextroute.application.route.routing.Enrichment;
import watoo.grd.nextroute.application.route.routing.RealtimeCoverage;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 보강 total 로 itinerary 정렬(FEASIBLE 우선·오름차순, 불가는 뒤). composer 실호출 + 실시간 없음(정적). */
class RouteTimelineServiceTest {

    private final Instant dep = Instant.parse("2026-09-08T01:00:00Z");
    private final Clock clock = Clock.fixed(dep, ZoneOffset.UTC);
    private final RouteTimelineComposer composer = new RouteTimelineComposer(
            clock, new TransferArrivalProperties(), new TransferPredictProperties(),
            new ConnectionFeasibilityEvaluator());
    private final RealtimeLegTimingLookup noRealtime = (l, s, b) -> Optional.empty();
    private final RouteTimelineService service = new RouteTimelineService(composer, noRealtime, clock);

    private RoutingItinerary itinArriving(String alightIso) {
        RoutingLeg bus = new RoutingLeg(RoutingMode.BUS, null, "1", "A1", "R", "143", "trip", null,
                dep.plusSeconds(60), Instant.parse(alightIso),
                (int) (Instant.parse(alightIso).getEpochSecond() - dep.plusSeconds(60).getEpochSecond()),
                List.of(new StopOccurrence("s", null, null, 0, 127.0, 37.5, "s")), List.of(), null,
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), null);
        return new RoutingItinerary(Instant.parse(alightIso), 0, 0, 0, 0, true, List.of(), null, List.of(bus));
    }

    private RoutingItinerary itinInfeasible() {
        RoutingLeg noTime = new RoutingLeg(RoutingMode.BUS, null, "1", "A1", "R", "143", "trip", null,
                null, null, 0, List.of(new StopOccurrence("s", null, null, 0, 127.0, 37.5, "s")),
                List.of(), null, Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), null);
        return new RoutingItinerary(null, 0, 0, 0, 0, true, List.of(), null, List.of(noTime));
    }

    private RoutingResult result(RoutingItinerary... its) {
        return new RoutingResult(RoutingProvider.OTP, "v2", "net", "g1", null, null, dep, List.of(), List.of(its));
    }

    private Instant firstAlight(RoutingResult r) {
        return r.itineraries().get(0).legs().get(0).alightAt();
    }

    @Test
    @DisplayName("보강 total 오름차순 정렬(느린→빠른 순 입력을 빠른 것 먼저로)")
    void sortsByEffectiveTotal() {
        RoutingResult in = result(
                itinArriving("2026-09-08T01:40:00Z"),  // 40분
                itinArriving("2026-09-08T01:20:00Z")); // 20분
        RoutingResult out = service.reorderByEffectiveTotal(in, dep, 5000L);
        assertThat(firstAlight(out)).isEqualTo(Instant.parse("2026-09-08T01:20:00Z")); // 빠른 것 먼저
    }

    @Test
    @DisplayName("불가(NO_CATCHABLE) itinerary 는 가능한 것 뒤로")
    void infeasibleAfterFeasible() {
        RoutingResult in = result(itinInfeasible(), itinArriving("2026-09-08T01:30:00Z"));
        RoutingResult out = service.reorderByEffectiveTotal(in, dep, 5000L);
        assertThat(firstAlight(out)).isEqualTo(Instant.parse("2026-09-08T01:30:00Z")); // feasible 먼저
        assertThat(out.itineraries().get(1).legs().get(0).boardAt()).isNull();        // 불가 뒤로
    }

    @Test
    @DisplayName("itinerary 0~1개 → 그대로")
    void noopSingle() {
        RoutingResult in = result(itinArriving("2026-09-08T01:20:00Z"));
        assertThat(service.reorderByEffectiveTotal(in, dep, 5000L)).isSameAs(in);
    }

    @Test
    @DisplayName("실시간이 실제로 total 을 바꿔 정렬 뒤집는다(coverage→lookup 적용 end-to-end)")
    void realtimeChangesOrder() {
        // A: 정적 20분, REALTIME_CAPABLE — 실시간이 30분으로 지연. B: 정적 20분.
        RoutingLeg aLeg = new RoutingLeg(RoutingMode.BUS, null, "1", "A1", "R", "143", "trip", null,
                dep.plusSeconds(60), dep.plusSeconds(1200), 1140,
                List.of(new StopOccurrence("s", null, null, 0, 127.0, 37.5, "s")), List.of(), null,
                Enrichment.staticOnly(RealtimeCoverage.REALTIME_CAPABLE), null);
        RoutingItinerary a = new RoutingItinerary(dep.plusSeconds(1200), 0, 0, 0, 0, true, List.of(), null, List.of(aLeg));
        RoutingItinerary b = itinArriving("2026-09-08T01:20:00Z"); // 정적 20분
        RealtimeLegTimingLookup delayA = (l, s, bd) -> l == aLeg
                ? Optional.of(new RealtimeLegTiming(dep.plusSeconds(60), dep.plusSeconds(1800),
                        false, watoo.grd.nextroute.application.route.routing.ActualSource.REALTIME, dep))
                : Optional.empty();
        RouteTimelineService svc = new RouteTimelineService(composer, delayA, clock);

        RoutingResult out = svc.reorderByEffectiveTotal(result(a, b), dep, 5000L);
        // A 가 실시간 30분으로 밀려 B(20분)가 먼저.
        assertThat(firstAlight(out)).isEqualTo(Instant.parse("2026-09-08T01:20:00Z"));
    }

    @Test
    @DisplayName("예산 0 → 실시간 skip(정적으로 합성). 실시간이면 밀릴 A 가 정적이라 먼저")
    void budgetZeroSkipsRealtime() {
        // A: 정적 15분(빠름), REALTIME_CAPABLE — 실시간이면 25분으로 밀림. B: 정적 20분.
        RoutingLeg aLeg = new RoutingLeg(RoutingMode.BUS, null, "1", "A1", "R", "143", "trip", null,
                dep.plusSeconds(60), dep.plusSeconds(900), 840,
                List.of(new StopOccurrence("s", null, null, 0, 127.0, 37.5, "s")), List.of(), null,
                Enrichment.staticOnly(RealtimeCoverage.REALTIME_CAPABLE), null);
        RoutingItinerary a = new RoutingItinerary(dep.plusSeconds(900), 0, 0, 0, 0, true, List.of(), null, List.of(aLeg));
        RoutingItinerary b = itinArriving("2026-09-08T01:20:00Z");
        RealtimeLegTimingLookup delayA = (l, s, bd) -> l == aLeg
                ? Optional.of(new RealtimeLegTiming(dep.plusSeconds(60), dep.plusSeconds(1500),
                        false, watoo.grd.nextroute.application.route.routing.ActualSource.REALTIME, dep))
                : Optional.empty();
        RouteTimelineService svc = new RouteTimelineService(composer, delayA, clock);

        // 예산 0 → 실시간 미적용 → A 정적 15분이 B 20분보다 먼저.
        RoutingResult out = svc.reorderByEffectiveTotal(result(a, b), dep, 0L);
        assertThat(firstAlight(out)).isEqualTo(dep.plusSeconds(900)); // A(01:15) 먼저
    }

    @Test
    @DisplayName("보강 total 동률 → 원래 순서 유지(안정 정렬)")
    void stableOnTie() {
        RoutingItinerary first = itinArriving("2026-09-08T01:30:00Z");
        RoutingItinerary second = itinArriving("2026-09-08T01:30:00Z");
        RoutingResult out = service.reorderByEffectiveTotal(result(first, second), dep, 5000L);
        assertThat(out.itineraries().get(0)).isSameAs(first);
        assertThat(out.itineraries().get(1)).isSameAs(second);
    }
}
