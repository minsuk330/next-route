package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.arrival.service.SubwayArrivalQueryService;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.service.TransferStopResolver;
import watoo.grd.nextroute.application.subway.service.DestinationNormalizer;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.bus;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.busLane;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.subway;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.subwayLane;

class JourneyTransitIdEnricherTest {

    private final TransferStopResolver busResolver = mock(TransferStopResolver.class);
    private final SubwayArrivalQueryService subwayArrivalQueryService =
            mock(SubwayArrivalQueryService.class);
    private final JourneyTransitIdEnricher enricher = new JourneyTransitIdEnricher(
            busResolver, subwayArrivalQueryService, new DestinationNormalizer());
    private final JourneyCompiler compiler = new JourneyCompiler(new StopIdentityResolver());

    @Test
    void bus_usesInternalStopRouteAndSequenceIds() {
        given(busResolver.resolveStop("ODSAY-A"))
                .willReturn(Optional.of(new TransferStopResolver.StopResolution("STOP-A")));
        given(busResolver.resolveStop("ODSAY-F"))
                .willReturn(Optional.of(new TransferStopResolver.StopResolution("STOP-F")));
        given(busResolver.resolveRoute("ODSAY-ROUTE", "370"))
                .willReturn(Optional.of(new TransferStopResolver.RouteResolution(
                        "100100576", "370", false, 8)));
        given(busResolver.resolveSeq("100100576", "STOP-A"))
                .willReturn(new TransferStopResolver.SeqResolution(List.of(4)));
        given(busResolver.resolveSeq("100100576", "STOP-F"))
                .willReturn(new TransferStopResolver.SeqResolution(List.of(12)));

        JourneyPlan plan = enricher.enrich(compile(bus(20).names("정류장A", "정류장F")
                .busStops("ODSAY-A", "ODSAY-F")
                .lanes(busLane("370", "ODSAY-ROUTE")).build()));

        JourneyLegDraft wait = plan.legs().get(0);
        JourneyLegDraft ride = plan.legs().get(1);
        assertThat(wait.startStopId()).isEqualTo("STOP-A");
        assertThat(wait.endStopId()).isEqualTo("STOP-A");
        assertThat(wait.sourceStartStopId()).isEqualTo("ODSAY-A");
        assertThat(wait.evidenceQuality()).isNull();
        assertThat(wait.routeOptions()).singleElement().satisfies(option -> {
            assertThat(option.routeId()).isEqualTo("100100576");
            assertThat(option.sourceRouteId()).isEqualTo("ODSAY-ROUTE");
            assertThat(option.boardingSeq()).isEqualTo(4);
            assertThat(option.alightingSeq()).isEqualTo(12);
        });
        assertThat(ride.startStopId()).isEqualTo("STOP-A");
        assertThat(ride.endStopId()).isEqualTo("STOP-F");
        assertThat(ride.evidenceQuality()).isNull();
    }

    @Test
    void ambiguousBusSequence_staysManualOnly() {
        given(busResolver.resolveStop("ODSAY-A"))
                .willReturn(Optional.of(new TransferStopResolver.StopResolution("STOP-A")));
        given(busResolver.resolveStop("ODSAY-F"))
                .willReturn(Optional.of(new TransferStopResolver.StopResolution("STOP-F")));
        given(busResolver.resolveRoute("ODSAY-ROUTE", "370"))
                .willReturn(Optional.of(new TransferStopResolver.RouteResolution(
                        "100100576", "370", false, 8)));
        given(busResolver.resolveSeq("100100576", "STOP-A"))
                .willReturn(new TransferStopResolver.SeqResolution(List.of(4, 18)));
        given(busResolver.resolveSeq("100100576", "STOP-F"))
                .willReturn(new TransferStopResolver.SeqResolution(List.of(12)));

        JourneyPlan plan = enricher.enrich(compile(bus(20)
                .busStops("ODSAY-A", "ODSAY-F")
                .lanes(busLane("370", "ODSAY-ROUTE")).build()));

        assertThat(plan.legs().get(0).evidenceQuality())
                .isEqualTo(JourneyLeg.EVIDENCE_QUALITY_UNKNOWN);
        assertThat(plan.legs().get(0).routeOptions().get(0).boardingSeq()).isNull();
    }

    @Test
    void subway_usesInternalLineAndStationIds_notOdsayIds() {
        given(subwayArrivalQueryService.resolveStation(37.4979, 127.0276, "1002"))
                .willReturn(Optional.of(new SubwayArrivalQueryService.ResolvedStation(
                        "222", "강남", "1002")));
        given(subwayArrivalQueryService.resolveStation(37.5551, 126.9369, "1002"))
                .willReturn(Optional.of(new SubwayArrivalQueryService.ResolvedStation(
                        "240", "신촌", "1002")));

        JourneyPlan plan = enricher.enrich(compile(subway(15).names("강남", "신촌")
                .coords(127.0276, 37.4979, 126.9369, 37.5551)
                .subwayStations(900222, 900240)
                .lanes(subwayLane("2호선", 2)).build()));

        JourneyLegDraft wait = plan.legs().get(0);
        JourneyLegDraft ride = plan.legs().get(1);
        assertThat(wait.startStopId()).isEqualTo("222");
        assertThat(wait.sourceStartStopId()).isEqualTo("900222");
        assertThat(wait.routeOptions().get(0).routeId()).isEqualTo("1002");
        assertThat(wait.routeOptions().get(0).sourceRouteId()).isEqualTo("2");
        assertThat(ride.endStopId()).isEqualTo("240");
        assertThat(ride.sourceEndStopId()).isEqualTo("900240");
        assertThat(ride.evidenceQuality()).isNull();
    }

    private JourneyPlan compile(watoo.grd.nextroute.application.route.dto.SubPathResult subPath) {
        return compiler.compile(new PathResult(1, null, List.of(subPath), List.of()));
    }
}
