package watoo.grd.nextroute.application.route.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.routing.ActualSource;
import watoo.grd.nextroute.application.route.routing.Enrichment;
import watoo.grd.nextroute.application.route.routing.RealtimeCoverage;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OTP leg coverage 배선: resolver 결과로 REALTIME_CAPABLE/STATIC_SCHEDULE 세팅. walk 은 불변(NONE). */
class OtpLegCoverageResolverTest {

    private final OtpSeoulIdentityResolver identity = mock(OtpSeoulIdentityResolver.class);
    private final OtpLegCoverageResolver resolver = new OtpLegCoverageResolver(identity);

    private RoutingLeg transit(RoutingMode mode, RealtimeCoverage cov) {
        return new RoutingLeg(mode, null, "1", "A1", "R", "143", "trip", null,
                Instant.parse("2026-09-08T01:00:00Z"), Instant.parse("2026-09-08T01:10:00Z"), 600,
                List.of(new StopOccurrence("1:BS_TAGO_SEB101000041", null, null, 0, 127.0, 37.5, "s")),
                List.of(), null, Enrichment.staticOnly(cov), null);
    }

    private RoutingLeg walk() {
        return new RoutingLeg(RoutingMode.WALK, null, "1", null, null, null, null, null,
                null, null, 0, List.of(), List.of(), null, Enrichment.staticOnly(RealtimeCoverage.NONE), null);
    }

    private RoutingResult result(RoutingLeg... legs) {
        RoutingItinerary it = new RoutingItinerary(Instant.parse("2026-09-08T01:10:00Z"),
                0, 0, 0, 0, true, List.of(), null, List.of(legs));
        return new RoutingResult(RoutingProvider.OTP, "v2", "net", "g1", null, null,
                Instant.parse("2026-09-08T01:00:00Z"), List.of(), List.of(it));
    }

    private RoutingLeg firstLeg(RoutingResult r) {
        return r.itineraries().get(0).legs().get(0);
    }

    @Test
    @DisplayName("resolver 매핑됨 → REALTIME_CAPABLE")
    void resolvable() {
        when(identity.resolve(any(), any())).thenReturn(Optional.of(new SeoulBusIdentity("s", "r", "143")));
        RoutingResult out = resolver.applyCoverage(result(transit(RoutingMode.BUS, RealtimeCoverage.STATIC_SCHEDULE)));
        assertThat(firstLeg(out).enrichment().coverage()).isEqualTo(RealtimeCoverage.REALTIME_CAPABLE);
    }

    @Test
    @DisplayName("resolver 미매핑 → STATIC_SCHEDULE 유지")
    void unresolvable() {
        when(identity.resolve(any(), any())).thenReturn(Optional.empty());
        RoutingResult out = resolver.applyCoverage(result(transit(RoutingMode.BUS, RealtimeCoverage.STATIC_SCHEDULE)));
        assertThat(firstLeg(out).enrichment().coverage()).isEqualTo(RealtimeCoverage.STATIC_SCHEDULE);
    }

    @Test
    @DisplayName("walk leg → resolver 미호출, coverage 불변(NONE)")
    void walkUnchanged() {
        RoutingResult out = resolver.applyCoverage(result(walk()));
        assertThat(firstLeg(out).enrichment().coverage()).isEqualTo(RealtimeCoverage.NONE);
        verify(identity, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("다른 enrichment 필드 보존, itinerary 개수 유지")
    void preservesShape() {
        when(identity.resolve(any(), any())).thenReturn(Optional.of(new SeoulBusIdentity("s", "r", "143")));
        RoutingResult in = result(transit(RoutingMode.SUBWAY, RealtimeCoverage.STATIC_SCHEDULE), walk());
        RoutingResult out = resolver.applyCoverage(in);
        assertThat(out.itineraries()).hasSize(1);
        assertThat(out.itineraries().get(0).legs()).hasSize(2);
        assertThat(firstLeg(out).enrichment().actualSource()).isEqualTo(ActualSource.STATIC);
        assertThat(out.provider()).isEqualTo(RoutingProvider.OTP); // result 필드 보존
    }
}
