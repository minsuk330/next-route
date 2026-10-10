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
import static org.mockito.Mockito.verify;
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
            assertThat(option.directionKey()).isEqualTo("SEQ:4>12");
        });
        assertThat(ride.startStopId()).isEqualTo("STOP-A");
        assertThat(ride.endStopId()).isEqualTo("STOP-F");
        assertThat(ride.evidenceQuality()).isNull();
    }

    /** 도시코드 없는 TAGO 노선(충주 103): 실시간 id 가 비어도 번호가 같은 서울 103 이 되면 안 된다. */
    @Test
    void regionalBusWithoutRealtimeId_doesNotFallBackToSeoulRouteNumber() {
        given(busResolver.resolveRoute(null, "103")).willReturn(Optional.of(
                new TransferStopResolver.RouteResolution("100100008", "103", true, 10)));

        JourneyPlan plan = enricher.enrich(compile(bus(20)
                .busStops("1:BS_TAGO_CJB283000123", "1:BS_TAGO_CJB283000456")
                .lanes(busLane("103", null)).build()));

        assertThat(plan.legs().get(0).routeOptions()).singleElement()
                .satisfies(option -> assertThat(option.routeId()).isNull());
        verify(busResolver).resolveRoute(null, null);
    }

    /** 서울 피드 정류장이면 번호 폴백은 그대로다(실시간 id 를 못 푼 서울 노선의 마지막 수단). */
    @Test
    void seoulGtfsStop_keepsRouteNumberFallback() {
        given(busResolver.resolveRoute(null, "370")).willReturn(Optional.of(
                new TransferStopResolver.RouteResolution("100100576", "370", true, 8)));

        JourneyPlan plan = enricher.enrich(compile(bus(20)
                .busStops("1:BS_TAGO_SEB121000091", "1:BS_TAGO_SEB121000092")
                .lanes(busLane("370", null)).build()));

        assertThat(plan.legs().get(0).routeOptions().get(0).routeId()).isEqualTo("100100576");
    }

    @Test
    void outsideSeoulGtfsStopShape() {
        assertThat(JourneyTransitIdEnricher.isOutsideSeoulGtfsStop("1:BS_TAGO_GGB202000141")).isTrue();
        assertThat(JourneyTransitIdEnricher.isOutsideSeoulGtfsStop("1:BS_ACC3_11000011")).isTrue();
        assertThat(JourneyTransitIdEnricher.isOutsideSeoulGtfsStop("1:BS_TAGO_SEB121000091")).isFalse();
        assertThat(JourneyTransitIdEnricher.isOutsideSeoulGtfsStop("121000091")).isFalse();
        assertThat(JourneyTransitIdEnricher.isOutsideSeoulGtfsStop(null)).isFalse();
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
                .wayCode(2)
                .lanes(subwayLane("2호선", 2)).build()));

        JourneyLegDraft wait = plan.legs().get(0);
        JourneyLegDraft ride = plan.legs().get(1);
        assertThat(wait.startStopId()).isEqualTo("222");
        assertThat(wait.sourceStartStopId()).isEqualTo("900222");
        assertThat(wait.routeOptions().get(0).routeId()).isEqualTo("1002");
        assertThat(wait.routeOptions().get(0).sourceRouteId()).isEqualTo("2");
        assertThat(wait.routeOptions().get(0).directionKey()).isEqualTo("WAY:2");
        assertThat(ride.endStopId()).isEqualTo("240");
        assertThat(ride.sourceEndStopId()).isEqualTo("900240");
        assertThat(ride.evidenceQuality()).isNull();
    }

    private JourneyPlan compile(watoo.grd.nextroute.application.route.dto.SubPathResult subPath) {
        return compiler.compile(new PathResult(1, null, List.of(subPath), List.of()));
    }
}
