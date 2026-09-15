package watoo.grd.nextroute.application.route.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties;
import watoo.grd.nextroute.application.route.config.RoutingProviderProperties;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.exception.OdSayApiException;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.routing.Enrichment;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.routing.Fare;
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

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** OTP 배선: default-provider=OTP+otp-enabled 일 때만 OTP 경로, ODsay 미호출. 기본(ODSAY)은 별도 테스트. */
class RouteSearchServiceOtpWiringTest {

    private final OdSayApiPort odsayNeverCalled = new OdSayApiPort() {
        @Override public RouteSearchResult searchPath(double sx, double sy, double ex, double ey, long t) {
            throw new AssertionError("OTP 경로에서 ODsay 가 호출되면 안 됨");
        }
        @Override public java.util.List<watoo.grd.nextroute.application.route.dto.LaneGraphicResult> loadLane(String m) {
            return List.of();
        }
    };

    /** permit 을 항상 내어주는 gate — 이 테스트는 gate 가 아니라 OTP 매핑/실패 변환을 본다. */
    private final OtpConcurrencyGate grantingGate = new OtpConcurrencyGate() {
        @Override public Optional<OtpPermit> tryAcquire(RoutingDeadline d, String role) {
            return Optional.of(new OtpPermit("t"));
        }
        @Override public void release(OtpPermit permit) { }
    };

    private RouteSearchService service(RoutingPort otpPort) {
        RoutingProviderProperties props = new RoutingProviderProperties();
        props.setDefaultProvider(RoutingProvider.OTP);
        props.setOtpEnabled(true);
        RoutingProviderSelector selector = new RoutingProviderSelector(props, List.of(otpPort));
        return new RouteSearchService(
                odsayNeverCalled, mock(watoo.grd.nextroute.application.route.port.out.TmapPedestrianPort.class),
                mock(RoutePolylineEnricher.class), mock(WalkSegmentEnricher.class),
                mock(TransferArrivalEnricher.class),
                new RouteSearchMetrics(new SimpleMeterRegistry()),
                new RouteSearchDeadlineProperties(),
                selector,
                new LegacyRouteResponseMapper(mock(watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver.class)),
                new DepartureTimeResolver(Clock.systemDefaultZone()), grantingGate, Clock.systemDefaultZone());
    }

    private RoutingPort otpReturning(RoutingOutcome outcome) {
        return new RoutingPort() {
            @Override public RoutingOutcome search(RoutingQuery q, RoutingDeadline d) { return outcome; }
            @Override public RoutingProvider provider() { return RoutingProvider.OTP; }
        };
    }

    @Test
    @DisplayName("OTP 경로: RoutingResult → legacy 응답, ODsay 미호출")
    void otpPathMapsToLegacy() {
        RoutingLeg bus = new RoutingLeg(RoutingMode.BUS, "BUS", "1", "1:A1", "1:R", "143",
                "1:T", null, Instant.now(), Instant.now(), 600,
                List.of(new StopOccurrence("1:S1", null, null, 0, 126.98, 37.55, "정류장1")),
                List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of());
        RoutingItinerary it = new RoutingItinerary(Instant.now(), 600, 0, 0, 0, false,
                List.of(), Fare.unknown(), List.of(bus));
        RoutingResult result = new RoutingResult(RoutingProvider.OTP, "v", "202503", "g1",
                null, null, Instant.now(), List.of(), List.of(it));

        RouteSearchResult legacy = service(otpReturning(result))
                .search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d"));

        assertThat(legacy.paths()).hasSize(1);
        assertThat(legacy.paths().get(0).subPaths().get(0).trafficType()).isEqualTo(2); // BUS
        assertThat(legacy.paths().get(0).info().payment()).isEqualTo(-1);              // UNKNOWN→-1
    }

    @Test
    @DisplayName("OTP OUT_OF_SCOPE → 빈 결과(무경로), TIMEOUT → 504 신호")
    void otpFailures() {
        RouteSearchResult empty = service(otpReturning(
                RoutingFailure.of(RoutingFailure.Kind.OUT_OF_SCOPE, "OUTSIDE_BOUNDS", "밖")))
                .search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d"));
        assertThat(empty.paths()).isEmpty();

        RouteSearchService svc = service(otpReturning(
                RoutingFailure.of(RoutingFailure.Kind.TIMEOUT, "T", "느림")));
        assertThatThrownBy(() -> svc.search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d")))
                .isInstanceOf(OdSayApiException.class)
                .satisfies(e -> assertThat(((OdSayApiException) e).getKind())
                        .isEqualTo(OdSayApiException.Kind.TIMEOUT));
    }
}
