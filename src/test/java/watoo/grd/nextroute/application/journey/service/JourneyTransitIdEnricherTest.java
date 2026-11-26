package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.arrival.service.SubwayArrivalQueryService;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.mapping.BusRealtimeStopIds;
import watoo.grd.nextroute.application.route.mapping.TagoCityCodeLookup;
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
    private final TagoCityCodeLookup tagoCityCodes = mock(TagoCityCodeLookup.class);
    private final JourneyTransitIdEnricher enricher = new JourneyTransitIdEnricher(
            busResolver, subwayArrivalQueryService, new DestinationNormalizer(),
            new BusRealtimeStopIds(tagoCityCodes));
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

        // #626: 서울 표에 없는 서울 GTFS 정류장도 실시간 9자리가 되고, 서울 노선이면 그 정류장으로 순번까지 맞춘다.
        given(busResolver.resolveSeq("100100576", "121000091"))
                .willReturn(new TransferStopResolver.SeqResolution(List.of(7)));
        given(busResolver.resolveSeq("100100576", "121000092"))
                .willReturn(new TransferStopResolver.SeqResolution(List.of()));

        JourneyPlan plan = enricher.enrich(compile(bus(20)
                .busStops("1:BS_TAGO_SEB121000091", "1:BS_TAGO_SEB121000092")
                .lanes(busLane("370", null)).build()));

        assertThat(plan.legs().get(0).startStopId()).isEqualTo("121000091");
        assertThat(plan.legs().get(0).routeOptions().get(0).routeId()).isEqualTo("100100576");
        assertThat(plan.legs().get(0).routeOptions().get(0).boardingSeq()).isEqualTo(7);
        assertThat(plan.legs().get(0).routeOptions().get(0).alightingSeq()).isNull();
    }

    @Test
    void outsideSeoulGtfsStopShape() {
        assertThat(JourneyTransitIdEnricher.isOutsideSeoulGtfsStop("1:BS_TAGO_GGB202000141")).isTrue();
        assertThat(JourneyTransitIdEnricher.isOutsideSeoulGtfsStop("1:BS_ACC3_11000011")).isTrue();
        assertThat(JourneyTransitIdEnricher.isOutsideSeoulGtfsStop("1:BS_TAGO_SEB121000091")).isFalse();
        assertThat(JourneyTransitIdEnricher.isOutsideSeoulGtfsStop("121000091")).isFalse();
        assertThat(JourneyTransitIdEnricher.isOutsideSeoulGtfsStop(null)).isFalse();
    }

    /**
     * #626: 경기 정류장은 서울 표에 없다. 저장된 정류장 id 가 비면 앱이 도착 시간을 못 묻는다 → 원천 9자리를 그대로 쓴다.
     * 경기 노선은 서울로 안 풀리므로 routeId·seq 는 비어 있고 근거 품질은 그대로 UNKNOWN 이다.
     */
    @Test
    void gyeonggiStopMissingFromSeoulTable_usesTheRealtimeId() {
        JourneyPlan plan = enricher.enrich(compile(bus(20).names("센트럴파크", "강남역")
                .busStops("233001674", "233001999")
                .lanes(busLane("1551", "234001138")).build()));

        JourneyLegDraft wait = plan.legs().get(0);
        JourneyLegDraft ride = plan.legs().get(1);
        assertThat(wait.startStopId()).isEqualTo("233001674");
        assertThat(ride.startStopId()).isEqualTo("233001674");
        assertThat(ride.endStopId()).isEqualTo("233001999");
        assertThat(wait.routeOptions()).singleElement().satisfies(option -> {
            assertThat(option.routeId()).isNull();
            assertThat(option.sourceRouteId()).isEqualTo("234001138");
            assertThat(option.boardingSeq()).isNull();
        });
        assertThat(wait.evidenceQuality()).isEqualTo(JourneyLeg.EVIDENCE_QUALITY_UNKNOWN);
    }

    /** 경기 노선이 서울 번호(1로 시작) 정류장에서 타는 경우. 운영 #626 의 서울 9자리 14건이 전부 이 모양이었다. */
    @Test
    void gyeonggiRouteAtSeoulNumberedStopMissingFromTable_usesTheRealtimeId() {
        JourneyPlan plan = enricher.enrich(compile(bus(20)
                .busStops("121000974", "122000123")
                .lanes(busLane("1550", "234000016")).build()));

        assertThat(plan.legs().get(0).startStopId()).isEqualTo("121000974");
        assertThat(plan.legs().get(1).endStopId()).isEqualTo("122000123");
        assertThat(plan.legs().get(0).routeOptions().get(0).routeId()).isNull();
    }

    /** TAGO 는 실시간을 물을 수 있는 도시 접두사일 때만 쓴다. */
    @Test
    void tagoStop_usesTheRealtimeIdOnlyForARealtimePrefix() {
        given(tagoCityCodes.isRealtimePrefix("BSB")).willReturn(true);

        JourneyPlan realtime = enricher.enrich(compile(bus(20)
                .busStops("BSB167850202", "BSB167850300")
                .lanes(busLane("100", "BSB5200100000")).build()));
        JourneyPlan unknown = enricher.enrich(compile(bus(20)
                .busStops("CJB283000123", "CJB283000456")
                .lanes(busLane("103", "CJB270000103")).build()));

        assertThat(realtime.legs().get(0).startStopId()).isEqualTo("BSB167850202");
        assertThat(realtime.legs().get(1).endStopId()).isEqualTo("BSB167850300");
        assertThat(unknown.legs().get(0).startStopId()).isNull();
        assertThat(unknown.legs().get(1).endStopId()).isNull();
    }

    /** GTFS 원형(검색 응답이 지역 id 를 못 줌)은 경기 GGB 9자리를 꺼낸다. 서울 밖 판정은 원형으로 그대로 한다. */
    @Test
    void gtfsGyeonggiStop_extractsTheRealtimeIdAndStaysOutsideSeoul() {
        given(busResolver.resolveRoute(null, "103")).willReturn(Optional.of(
                new TransferStopResolver.RouteResolution("100100008", "103", true, 10)));

        JourneyPlan plan = enricher.enrich(compile(bus(20)
                .busStops("1:BS_TAGO_GGB202000141", "1:BS_TAGO_GGB202000142")
                .lanes(busLane("103", null)).build()));

        assertThat(plan.legs().get(0).startStopId()).isEqualTo("202000141");
        assertThat(plan.legs().get(1).endStopId()).isEqualTo("202000142");
        assertThat(plan.legs().get(0).routeOptions().get(0).routeId()).isNull();   // 서울 103 으로 둔갑하지 않음
        verify(busResolver).resolveRoute(null, null);
    }

    /** 모르는 모양(ODsay 내부 id 등)은 지금처럼 null 이다. */
    @Test
    void unknownStopShape_staysNull() {
        JourneyPlan plan = enricher.enrich(compile(bus(20)
                .busStops("ODSAY-X", "ODSAY-Y")
                .lanes(busLane("370", "ODSAY-ROUTE")).build()));

        assertThat(plan.legs().get(0).startStopId()).isNull();
        assertThat(plan.legs().get(1).endStopId()).isNull();
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
