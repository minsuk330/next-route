package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import watoo.grd.nextroute.application.journey.config.JourneyTrackProperties;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.StartTrackCommand;
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;
import watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties;
import watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternDetail;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.service.LegacyRouteResponseMapper;
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.CommonErrorCode;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;
import watoo.grd.nextroute.testsupport.InMemoryRegionalBusRouteStopStore;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("단독 추적 시작")
class TrackJourneyStartServiceTest {

    /** 2026-10-07 12:00 KST. */
    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-10-07");
    private static final String ROUTE = "1:RR_ACC1_S-1-02-1I";

    private final TransitPatternPort port = mock(TransitPatternPort.class);
    private final OtpConcurrencyGate gate = mock(OtpConcurrencyGate.class);
    private final JourneyStartTxService txService = mock(JourneyStartTxService.class);
    private final JourneyTransitIdEnricher enricher = mock(JourneyTransitIdEnricher.class);
    private final JourneyTrackProperties properties = new JourneyTrackProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private TrackJourneyStartService service;

    private final TransitPatternDetail line2 = TrackFixtures.line2Detail();
    /** 강남 다음 4번째 역(내선). */
    private final String alight = line2.stops().get(35).gtfsId();

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        given(gate.tryAcquire(any(), anyString())).willReturn(Optional.of(new OtpPermit("p")));
        given(enricher.enrich(any())).willAnswer(returnsFirstArg());
        given(port.patternsAtStop(eq(TrackFixtures.GANGNAM), any()))
                .willReturn(List.of(TrackFixtures.lightOf(line2, 2)));
        given(port.pattern(eq(TrackFixtures.LINE2_PATTERN), any())).willReturn(Optional.of(line2));
        given(port.tripsForDate(eq(TrackFixtures.LINE2_PATTERN), eq(DAY), any()))
                .willReturn(TrackFixtures.line2Trips());
        given(port.tripStopTimes(anyString(), eq(DAY), any())).willReturn(TrackFixtures.line2Times());

        TrackPatternSource source = new TrackPatternSource(port, gate, new OtpRoutingProperties(),
                new RouteSearchDeadlineProperties(), properties, clock);
        LegacyRouteResponseMapper legacy = new LegacyRouteResponseMapper(new OtpSeoulIdentityResolver(
                mock(BusStopRepository.class), mock(BusRouteRepository.class), mock(SubwayStationRepository.class),
                InMemoryRegionalBusRouteStopStore.prefixTableOnlyLookup()));
        service = new TrackJourneyStartService(properties, source, legacy,
                new JourneyCompiler(new StopIdentityResolver()), enricher,
                new JourneyGuidanceInfoEnricher(org.mockito.Mockito.mock(watoo.grd.nextroute.domain.bus.repository.BusStopRepository.class), org.mockito.Mockito.mock(watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteStopStore.class), new watoo.grd.nextroute.application.subway.service.SubwayEntranceIndex(), new watoo.grd.nextroute.application.subway.service.SubwayFastAlightIndex(), new watoo.grd.nextroute.application.subway.service.SubwayDoorSideIndex(), new watoo.grd.nextroute.application.subway.service.SubwayLineEnds(org.mockito.Mockito.mock(watoo.grd.nextroute.application.subway.service.SubwayLinePatternService.class))), txService,
                new JourneyStartConflictTranslator(mock(JourneyQueryService.class)),
                new ObjectMapper(), meters, clock,
                watoo.grd.nextroute.application.subway.service.SubwayExpressRouteFixtures.railServiceClassifier());
    }

    @Test
    @DisplayName("정상: kind=TRACK, 대기+탑승 두 단계, 목적지 = 하차역, permit 반납")
    void starts() {
        service.start(1L, new StartTrackCommand(ROUTE, TrackFixtures.GANGNAM, alight, null, false));

        ArgumentCaptor<JourneyPlan> plan = ArgumentCaptor.forClass(JourneyPlan.class);
        ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> destination =
                ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(txService).start(eq(1L), plan.capture(), any(), destination.capture(), eq(false), eq(NOW),
                eq(JourneyKind.TRACK));
        assertThat(plan.getValue().legs()).extracting(JourneyLegDraft::type)
                .containsExactly(JourneyLegType.WAIT_SUBWAY, JourneyLegType.RIDE_SUBWAY);
        assertThat(destination.getValue().path("name").asText()).isEqualTo(line2.stops().get(35).name());
        verify(gate).release(any());
        assertThat(meters.counter("track.start", "mode", "SUBWAY", "result", "started").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("단독 추적은 어댑터를 안 거쳐도 분류기로 열차 종류를 붙인다 — 2호선은 NORMAL, 스냅샷·단계 둘 다(subway-express S2)")
    void classifiesTrainKind() {
        service.start(1L, new StartTrackCommand(ROUTE, TrackFixtures.GANGNAM, alight, null, false));

        ArgumentCaptor<JourneyPlan> plan = ArgumentCaptor.forClass(JourneyPlan.class);
        ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> snapshot =
                ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(txService).start(eq(1L), plan.capture(), snapshot.capture(), any(), eq(false), eq(NOW),
                eq(JourneyKind.TRACK));
        assertThat(plan.getValue().legs()).extracting(leg -> leg.guidance().trainKind())
                .containsExactly("NORMAL", "NORMAL");
        assertThat(snapshot.getValue().path("subPaths").get(0).path("trainKind").asText()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("같은 역 두 번(#546): alightIndex 면 두 번째 통과까지 타고, 없으면 첫 통과에서 내린다")
    void startsToSecondPass() {
        int board = 0;
        while (!line2.stops().get(board).gtfsId().equals(TrackFixtures.GANGNAM)) {
            board++;
        }
        List<TransitStop> stops = new java.util.ArrayList<>(line2.stops());
        stops.set(board + 4, stops.get(board + 2));
        TransitPatternDetail repeat = new TransitPatternDetail(line2.code(), line2.route(), stops,
                line2.geometryPoints());
        given(port.patternsAtStop(eq(TrackFixtures.GANGNAM), any()))
                .willReturn(List.of(TrackFixtures.lightOf(repeat, 2)));
        given(port.pattern(eq(TrackFixtures.LINE2_PATTERN), any())).willReturn(Optional.of(repeat));
        String twice = stops.get(board + 2).gtfsId();
        List<TransitPatternPort.TransitStopTime> times = new java.util.ArrayList<>(TrackFixtures.line2Times());
        TransitPatternPort.TransitStopTime replaced = times.get(board + 4);
        times.set(board + 4, new TransitPatternPort.TransitStopTime(twice, replaced.scheduledArrival(),
                replaced.scheduledDeparture()));
        given(port.tripStopTimes(anyString(), eq(DAY), any())).willReturn(times);

        service.start(1L, new StartTrackCommand(ROUTE, TrackFixtures.GANGNAM, twice, null, false));
        service.start(1L, new StartTrackCommand(ROUTE, TrackFixtures.GANGNAM, twice, null, 3, false));

        ArgumentCaptor<JourneyPlan> plans = ArgumentCaptor.forClass(JourneyPlan.class);
        verify(txService, org.mockito.Mockito.times(2)).start(eq(1L), plans.capture(), any(), any(), eq(false),
                eq(NOW), eq(JourneyKind.TRACK));
        JourneyLegDraft first = plans.getAllValues().get(0).legs().get(1);
        JourneyLegDraft second = plans.getAllValues().get(1).legs().get(1);
        assertThat(second.endStopId()).isEqualTo(first.endStopId());
        assertThat(second.plannedDurationSec()).isGreaterThan(first.plannedDurationSec());
    }

    @Test
    @DisplayName("오늘 첫 출발이 지금 이전인 trip 중 가장 늦은 것을 고른다")
    void picksTripNearNow() {
        service.start(1L, new StartTrackCommand(ROUTE, TrackFixtures.GANGNAM, alight, null, false));

        // 픽스처: 22140(06:09) / 66180(18:23). 12:00 이면 06:09 출발 trip.
        verify(port).tripStopTimes(eq("1:RR_ACC1_S-1-02-1I_Ord013"), eq(DAY), any());
    }

    @Test
    @DisplayName("플래그가 꺼져 있으면 404 — OTP 를 부르지 않는다")
    void disabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.start(1L,
                new StartTrackCommand(ROUTE, TrackFixtures.GANGNAM, alight, null, false)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        verifyNoInteractions(port, gate, txService);
    }

    @Test
    @DisplayName("하차 금지 정류장에서 내리거나 승차 금지 정류장에서 타면 422 TRACK_STOPS_INVALID")
    void blockedBoardOrAlight() {
        given(port.tripStopTimes(anyString(), eq(DAY), any()))
                .willReturn(TrackFixtures.line2TimesBlocking(Set.of(), Set.of(35)));
        assertThatThrownBy(() -> service.start(1L,
                new StartTrackCommand(ROUTE, TrackFixtures.GANGNAM, alight, null, false)))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRACK_STOPS_INVALID);

        given(port.tripStopTimes(anyString(), eq(DAY), any()))
                .willReturn(TrackFixtures.line2TimesBlocking(Set.of(31), Set.of()));
        assertThatThrownBy(() -> service.start(1L,
                new StartTrackCommand(ROUTE, TrackFixtures.GANGNAM, alight, null, false)))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRACK_STOPS_INVALID);
        verify(txService, never()).start(any(), any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("그 노선이 승차 뒤 한 바퀴 안에 지나지 않는 하차면 422 TRACK_STOPS_INVALID")
    void unreachableAlight() {
        // 2호선은 순환이라 승차 "앞" 역도 한 바퀴 안에서 닿는다(정상). 노선에 없는 역으로 확인한다.
        String before = "1:RS_ACC1_S-1-0999";

        assertThatThrownBy(() -> service.start(1L,
                new StartTrackCommand(ROUTE, TrackFixtures.GANGNAM, before, null, false)))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRACK_STOPS_INVALID);
        verify(txService, never()).start(any(), any(), any(), any(), anyBoolean(), any(), any());
        verify(gate).release(any());
    }

    @Test
    @DisplayName("낡은 패턴 코드면 노선·승하차로 다시 찾는다")
    void stalePatternCode() {
        service.start(1L, new StartTrackCommand(ROUTE, TrackFixtures.GANGNAM, alight, "1:OLD::99", false));

        verify(port).pattern(eq(TrackFixtures.LINE2_PATTERN), any());
        verify(txService).start(eq(1L), any(), any(), any(), eq(false), eq(NOW), eq(JourneyKind.TRACK));
    }

    @Test
    @DisplayName("OTP permit 을 못 얻으면 503 TRACK_PATTERN_UNAVAILABLE")
    void saturated() {
        given(gate.tryAcquire(any(), anyString())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(1L,
                new StartTrackCommand(ROUTE, TrackFixtures.GANGNAM, alight, null, false)))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRACK_PATTERN_UNAVAILABLE);
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("OTP 호출 실패는 503, permit 은 반납")
    void otpFailure() {
        given(port.patternsAtStop(anyString(), any()))
                .willThrow(new TransitPatternPort.TransitPatternUnavailableException("boom"));

        assertThatThrownBy(() -> service.start(1L,
                new StartTrackCommand(ROUTE, TrackFixtures.GANGNAM, alight, null, false)))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRACK_PATTERN_UNAVAILABLE);
        verify(gate).release(any());
    }

    @Test
    @DisplayName("오늘·어제 운행이 없으면 422 TRACK_SCHEDULE_UNAVAILABLE(근사하지 않는다)")
    void noTrips() {
        given(port.tripsForDate(anyString(), any(), any())).willReturn(List.of());

        assertThatThrownBy(() -> service.start(1L,
                new StartTrackCommand(ROUTE, TrackFixtures.GANGNAM, alight, null, false)))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRACK_SCHEDULE_UNAVAILABLE);
    }

    @Test
    @DisplayName("정차 시각이 패턴과 어긋나면 422 TRACK_SCHEDULE_UNAVAILABLE")
    void mismatchedTimes() {
        given(port.tripStopTimes(anyString(), any(), any())).willReturn(List.of());

        assertThatThrownBy(() -> service.start(1L,
                new StartTrackCommand(ROUTE, TrackFixtures.GANGNAM, alight, null, false)))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRACK_SCHEDULE_UNAVAILABLE);
    }

    @Test
    @DisplayName("replace 를 그대로 넘긴다(진행 중 일반 여정도 접힌다)")
    void replacePassesThrough() {
        service.start(1L, new StartTrackCommand(ROUTE, TrackFixtures.GANGNAM, alight, null, true));

        verify(txService).start(eq(1L), any(), any(), any(), eq(true), eq(NOW), eq(JourneyKind.TRACK));
    }

    @Test
    @DisplayName("하위 호환: 서울 stop id 로 경기 노선을 시작하면 경기 stop 에서 탄다(형제 정류장)")
    void startsFromSiblingStop() {
        String seb = "1:BS_TAGO_SEB121000009";
        String ggb = "1:BS_TAGO_GGB121000009";
        given(port.stops(any(), any())).willReturn(watoo.grd.nextroute.infrastructure.adapter.out.api.otp
                .OtpTransitPatternAdapter.parseStopsByIds(TrackFixtures.json("stops-by-ids-sinbundang.json")));
        for (String id : List.of("SEB121000009", "GGB121000009", "ICB121000009")) {
            given(port.patternsAtStop(eq("1:BS_TAGO_" + id), any())).willReturn(watoo.grd.nextroute.infrastructure
                    .adapter.out.api.otp.OtpTransitPatternAdapter
                    .parsePatternsAtStop(TrackFixtures.json("patterns-at-stop-" + id + ".json")));
        }
        TransitPatternPort.TransitPattern p5100 = port.patternsAtStop(ggb, null).stream()
                .filter(p -> p.route().gtfsId().equals("1:BR_TAGO_GGB200000115")).findFirst().orElseThrow();
        int board = p5100.stops().stream().map(TransitPatternPort.PatternStop::gtfsId).toList().indexOf(ggb);
        String alightStop = p5100.stops().get(board + 3).gtfsId();
        // 상세가 없으면 503 으로 멈춘다 — 그 전에 경기 stop 기준으로 패턴을 골랐는지만 본다
        given(port.pattern(eq(p5100.code()), any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(1L,
                new StartTrackCommand("1:BR_TAGO_GGB200000115", seb, alightStop, null, false)))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRACK_PATTERN_UNAVAILABLE);
        verify(port).pattern(eq(p5100.code()), any());
    }

}
