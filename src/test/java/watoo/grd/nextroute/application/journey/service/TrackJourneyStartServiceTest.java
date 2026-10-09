package watoo.grd.nextroute.application.journey.service;

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
                new JourneyCompiler(new StopIdentityResolver()), enricher, txService,
                new JourneyStartConflictTranslator(mock(JourneyQueryService.class)),
                new ObjectMapper(), meters, clock);
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
}
