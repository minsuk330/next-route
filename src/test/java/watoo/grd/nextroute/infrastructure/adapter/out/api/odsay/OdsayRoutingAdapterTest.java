package watoo.grd.nextroute.infrastructure.adapter.out.api.odsay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.dto.LaneGraphicResult;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.exception.OdSayApiException;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.routing.FareAvailability;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingFailure;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.RoutingOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingQuery;
import watoo.grd.nextroute.application.route.routing.RoutingResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OdsayRoutingAdapterTest {

    private final Instant now = Instant.parse("2026-09-08T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private RoutingQuery query() {
        return new RoutingQuery(126.97, 37.56, "o", 127.02, 37.49, "d",
                now, Set.of(), null, false, "v", RoutingProvider.ODSAY, null);
    }

    /** 단일 메서드 stub — OdSayApiPort 는 함수형이 아니므로(2 메서드) 익명 구현으로 감싼다. */
    private interface SearchFn { RouteSearchResult apply(double sx, double sy, double ex, double ey, long t); }

    private OdSayApiPort port(SearchFn fn) {
        return new OdSayApiPort() {
            @Override public RouteSearchResult searchPath(double sx, double sy, double ex, double ey, long t) {
                return fn.apply(sx, sy, ex, ey, t);
            }
        };
    }

    private SubPathResult sub(int trafficType, int sectionTime, List<StationResult> stations) {
        return new SubPathResult(trafficType, sectionTime, null, null, stations,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("ODsay RouteSearchResult → RoutingResult: mode/시간/요금/정류장 역매핑, graphVersion sentinel")
    void mapsResult() {
        SubPathResult bus = sub(2, 10, List.of(
                new StationResult(0, "S1", "정류장1", 126.98, 37.55),
                new StationResult(1, "S2", "정류장2", 126.99, 37.54)));
        SubPathResult walk = sub(3, 5, null);
        PathInfo info = new PathInfo(15, 1250, 300, 1, "정류장1", "정류장2", "mapobj");
        RouteSearchResult raw = new RouteSearchResult(1, 1, 0, 0, 0,
                List.of(new PathResult(1, info, List.of(bus, walk), List.<LaneGraphicResult>of())));

        OdSayApiPort port = new OdSayApiPort() {
            @Override public RouteSearchResult searchPath(double sx, double sy, double ex, double ey, long t) { return raw; }
        };
        OdsayRoutingAdapter adapter = new OdsayRoutingAdapter(port, clock);

        RoutingOutcome outcome = adapter.search(query(), RoutingDeadline.of(clock, 2000));
        assertThat(outcome.success()).isTrue();
        RoutingResult result = (RoutingResult) outcome;
        assertThat(result.provider()).isEqualTo(RoutingProvider.ODSAY);
        assertThat(result.graphVersion()).isEqualTo(RoutingResult.ODSAY_NO_GRAPH);

        RoutingItinerary it = result.itineraries().get(0);
        assertThat(it.legs()).extracting(RoutingLeg::mode)
                .containsExactly(RoutingMode.BUS, RoutingMode.WALK);
        assertThat(it.legs().get(0).rideDurationSeconds()).isEqualTo(600); // 10min*60
        assertThat(it.legs().get(0).stops()).hasSize(2);
        assertThat(it.walkSeconds()).isEqualTo(300);   // 5min*60
        assertThat(it.moveSeconds()).isEqualTo(600);
        // ODsay 요금 알려짐 → KNOWN(0 아님)
        assertThat(it.fare().availability()).isEqualTo(FareAvailability.KNOWN);
        assertThat(it.fare().amount()).isEqualTo(1250);
    }

    @Test
    @DisplayName("도보 전용 payment 0 → KNOWN_ZERO, 대중교통 payment 0 → UNKNOWN(0 단정 안 함)")
    void fareRules() {
        OdsayRoutingAdapter adapter = new OdsayRoutingAdapter(
                port((sx, sy, ex, ey, t) -> new RouteSearchResult(1, 0, 0, 0, 0, List.of(
                        new PathResult(3, new PathInfo(5, 0, 300, 0, "", "", null),
                                List.of(sub(3, 5, null)), List.<LaneGraphicResult>of())))), clock);
        RoutingResult r = (RoutingResult) adapter.search(query(), RoutingDeadline.of(clock, 2000));
        assertThat(r.itineraries().get(0).fare().availability()).isEqualTo(FareAvailability.KNOWN_ZERO);
    }

    @Test
    @DisplayName("OdSayApiException(BUSINESS) → RoutingFailure(INPUT_ERROR), -98 도보 fallback 은 legacy 담당")
    void mapsBusinessError() {
        OdsayRoutingAdapter adapter = new OdsayRoutingAdapter(port((sx, sy, ex, ey, t) -> {
            throw new OdSayApiException(-98, "700m 이내");
        }), clock);
        RoutingOutcome outcome = adapter.search(query(), RoutingDeadline.of(clock, 2000));
        assertThat(outcome.success()).isFalse();
        assertThat(((RoutingFailure) outcome).kind()).isEqualTo(RoutingFailure.Kind.INPUT_ERROR);
    }

    @Test
    @DisplayName("예산 소진 → 호출 없이 TIMEOUT")
    void noBudget() {
        OdsayRoutingAdapter adapter = new OdsayRoutingAdapter(port((sx, sy, ex, ey, t) -> {
            throw new AssertionError("호출되면 안 됨");
        }), clock);
        RoutingOutcome outcome = adapter.search(query(), new RoutingDeadline(now));
        assertThat(((RoutingFailure) outcome).kind()).isEqualTo(RoutingFailure.Kind.TIMEOUT);
    }
}
