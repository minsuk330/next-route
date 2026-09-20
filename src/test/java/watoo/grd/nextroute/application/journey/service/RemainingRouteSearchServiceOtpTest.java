package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.service.JourneyWaypointResolver.Coord;
import watoo.grd.nextroute.application.journey.service.JourneyWaypointResolver.Waypoints;
import watoo.grd.nextroute.application.route.config.RoutingProviderProperties;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.routing.Enrichment;
import watoo.grd.nextroute.application.route.routing.Fare;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.routing.LegGeometry;
import watoo.grd.nextroute.application.route.routing.RealtimeCoverage;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingFailure;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.RoutingOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingPort;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingProviderSelector;
import watoo.grd.nextroute.application.route.routing.RoutingQuery;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;
import watoo.grd.nextroute.application.route.service.LegacyRouteResponseMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 재탐색(RemainingRouteSearchService)의 OTP 경로. 기본 ODSAY 는 기존 테스트가 커버. */
class RemainingRouteSearchServiceOtpTest {

    private static final Waypoints WP = new Waypoints(new Coord(127.0, 37.5), new Coord(127.1, 37.4));
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);

    private final OdSayApiPort odsayNeverCalled = new OdSayApiPort() {
        @Override public watoo.grd.nextroute.application.route.dto.RouteSearchResult searchPath(
                double sx, double sy, double ex, double ey, long t) {
            throw new AssertionError("OTP 재탐색에서 ODsay 호출 금지");
        }
    };

    private RemainingRouteSearchService service(RoutingOutcome otpOutcome) {
        RoutingProviderProperties props = new RoutingProviderProperties();
        props.setDefaultProvider(RoutingProvider.OTP);
        props.setOtpEnabled(true);
        RoutingPort otp = new RoutingPort() {
            @Override public RoutingOutcome search(RoutingQuery q, RoutingDeadline d) { return otpOutcome; }
            @Override public RoutingProvider provider() { return RoutingProvider.OTP; }
        };
        OtpConcurrencyGate grantingGate = new OtpConcurrencyGate() {
            @Override public Optional<OtpPermit> tryAcquire(RoutingDeadline d, String role) {
                return Optional.of(new OtpPermit("t"));
            }
            @Override public void release(OtpPermit permit) { }
        };
        return new RemainingRouteSearchService(odsayNeverCalled,
                new JourneyRerouteMetrics(new SimpleMeterRegistry()),
                new RoutingProviderSelector(props, List.of(otp)),
                new LegacyRouteResponseMapper(mock(watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver.class),
                        mock(watoo.grd.nextroute.domain.bus.repository.BusRouteRepository.class)), grantingGate, new watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties(), clock);
    }

    @Test
    @DisplayName("OTP 성공 → legacy paths, ODsay 미호출")
    void otpSuccess() {
        RoutingLeg bus = new RoutingLeg(RoutingMode.BUS, "BUS", "1", "1:A1", "1:R", "143",
                "1:T", null, Instant.now(), Instant.now(), 600,
                List.of(new StopOccurrence("1:S1", null, null, 0, 127.0, 37.5, "정류장")),
                List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of());
        RoutingItinerary it = new RoutingItinerary(Instant.now(), 600, 0, 0, 0, false,
                List.of(), Fare.unknown(), List.of(bus));
        RoutingResult result = new RoutingResult(RoutingProvider.OTP, "v", "202503", "g",
                null, null, Instant.now(), List.of(), List.of(it));

        List<PathResult> paths = service(result).search("1:S1", WP, 2000);
        assertThat(paths).hasSize(1);
        assertThat(paths.get(0).subPaths().get(0).trafficType()).isEqualTo(2); // BUS
    }

    @Test
    @DisplayName("OTP 실패 → 빈 목록(재탐색 계약), 예외 안 던짐")
    void otpFailureEmpty() {
        List<PathResult> paths = service(
                RoutingFailure.of(RoutingFailure.Kind.NO_ROUTE, "NO", "없음"))
                .search("1:S1", WP, 2000);
        assertThat(paths).isEmpty();
    }
}
