package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyTrackProperties;
import watoo.grd.nextroute.application.journey.dto.TrackViews;
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;
import watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternDetail;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.CommonErrorCode;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("단독 추적 선택 조회")
class TrackSelectionQueryServiceTest {

    private final TransitPatternPort port = mock(TransitPatternPort.class);
    private final OtpConcurrencyGate gate = mock(OtpConcurrencyGate.class);
    private final JourneyTrackProperties properties = new JourneyTrackProperties();
    private final TransitPatternDetail line2 = TrackFixtures.line2Detail();
    private final TrackRealtimeIdResolver realtimeIds = mock(TrackRealtimeIdResolver.class);
    private TrackSelectionQueryService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        given(gate.tryAcquire(any(), anyString())).willReturn(Optional.of(new OtpPermit("p")));
        given(port.patternsAtStop(eq(TrackFixtures.GANGNAM), any())).willReturn(List.of(TrackFixtures.lightOf(line2, 2)));
        given(port.pattern(eq(TrackFixtures.LINE2_PATTERN), any())).willReturn(Optional.of(line2));
        service = new TrackSelectionQueryService(properties, new TrackPatternSource(port, gate,
                new OtpRoutingProperties(), new RouteSearchDeadlineProperties(), properties,
                Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneOffset.UTC)), realtimeIds);
        given(realtimeIds.busStop(any())).willReturn(new TrackRealtimeIdResolver.BusStopIds(null, null));
        given(realtimeIds.subway(any(), any())).willReturn(List.of(
                new TrackRealtimeIdResolver.SubwayStationIds(null, null, null)));
    }

    @Test
    @DisplayName("플래그 off 면 404, OTP 를 부르지 않는다")
    void disabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.directions(TrackFixtures.GANGNAM))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        verifyNoInteractions(port, gate);
    }

    @Test
    @DisplayName("방향: 2호선 내선 순환 하나, 색은 # 를 붙인다. 정류장 패턴은 캐시된다")
    void directions() {
        List<TrackViews.Direction> directions = service.directions(TrackFixtures.GANGNAM);
        service.directions(TrackFixtures.GANGNAM);

        assertThat(directions).singleElement().satisfies(d -> {
            assertThat(d.directionLabel()).isEqualTo("내선");
            assertThat(d.color()).isEqualTo("#00A23F");
            assertThat(d.mode()).isEqualTo(RoutingMode.SUBWAY);
            assertThat(d.patternCode()).isEqualTo(TrackFixtures.LINE2_PATTERN);
        });
        verify(port, times(1)).patternsAtStop(eq(TrackFixtures.GANGNAM), any());
    }

    @Test
    @DisplayName("하차 후보: 승차 다음부터 한 바퀴(다시 강남 직전까지), index 0 부터, 좌표 포함")
    void alightStops() {
        List<TrackViews.AlightStop> stops = service.alightStops("1:RR_ACC1_S-1-02-1I", TrackFixtures.GANGNAM, null);

        assertThat(stops).hasSize(74 - 31 - 1);
        assertThat(stops.get(0).gtfsStopId()).isEqualTo(line2.stops().get(32).gtfsId());
        assertThat(stops.get(0).index()).isZero();
        assertThat(stops).noneMatch(s -> s.gtfsStopId().equals(TrackFixtures.GANGNAM));
        assertThat(stops.get(0).lat()).isEqualTo(line2.stops().get(32).lat());
    }

    @Test
    @DisplayName("그 노선이 그 정류장을 안 지나면 422")
    void wrongRoute() {
        assertThatThrownBy(() -> service.alightStops("1:OTHER", TrackFixtures.GANGNAM, null))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRACK_STOPS_INVALID);
    }

    @Test
    @DisplayName("좌표 주변: SUBWAY 는 철도 포함, BUS 는 버스만")
    void stopsNearFilter() {
        given(port.stopsNear(anyDouble(), anyDouble(), anyInt(), anyInt(), any())).willReturn(List.of(
                near(new TransitStop("b", "버스", 37.5, 127.0, RoutingMode.BUS)),
                near(new TransitStop("s", "지하철", 37.5, 127.0, RoutingMode.SUBWAY)),
                near(new TransitStop("r", "철도", 37.5, 127.0, RoutingMode.RAIL))));

        assertThat(service.stopsNear(37.5, 127.0, RoutingMode.SUBWAY)).extracting(TrackViews.Stop::gtfsStopId)
                .containsExactly("s", "r");
        assertThat(service.stopsNear(37.5, 127.0, RoutingMode.BUS)).extracting(TrackViews.Stop::gtfsStopId)
                .containsExactly("b");
        assertThat(service.stopsNear(37.5, 127.0, null)).hasSize(3);
    }

    @Test
    @DisplayName("#313 주변 정류장에 실시간 id — 버스는 정류장 id·ARS, 지하철은 호선마다 한 줄")
    void stopsNearCarriesRealtimeIds() {
        TransitStop bus = new TransitStop("1:BS_TAGO_SEB121000091", "강남역9번출구", 37.4978, 127.027, RoutingMode.BUS);
        TransitStop subway = new TransitStop("1:RS_ACC1_S-1-X", "환승역", 37.5, 127.0, RoutingMode.SUBWAY);
        given(port.stopsNear(anyDouble(), anyDouble(), anyInt(), anyInt(), any())).willReturn(List.of(
                new TransitPatternPort.NearStop(bus, 67, List.of("8146")),
                new TransitPatternPort.NearStop(subway, 90, List.of("서울2호선", "신분당선"))));
        given(realtimeIds.busStop("1:BS_TAGO_SEB121000091"))
                .willReturn(new TrackRealtimeIdResolver.BusStopIds("121000091", "22009"));
        given(realtimeIds.subway(subway, List.of("서울2호선", "신분당선"))).willReturn(List.of(
                new TrackRealtimeIdResolver.SubwayStationIds("1002000222", "1002", "2호선"),
                new TrackRealtimeIdResolver.SubwayStationIds("1077000687", "1077", "신분당선")));

        List<TrackViews.Stop> stops = service.stopsNear(37.5, 127.0, null);

        assertThat(stops).hasSize(3);
        assertThat(stops.get(0)).extracting(TrackViews.Stop::arrivalStopId, TrackViews.Stop::arsId,
                TrackViews.Stop::distanceMeters).containsExactly("121000091", "22009", 67);
        assertThat(stops.subList(1, 3)).extracting(TrackViews.Stop::gtfsStopId).containsOnly("1:RS_ACC1_S-1-X");
        assertThat(stops.subList(1, 3)).extracting(TrackViews.Stop::arrivalStationId)
                .containsExactly("1002000222", "1077000687");
    }

    @Test
    @DisplayName("#313 방향: 지하철은 arrivalRouteId 가 null — 역 도착 API 는 역 id 만 받는다")
    void subwayDirectionHasNoRouteId() {
        assertThat(service.directions(TrackFixtures.GANGNAM)).extracting(TrackViews.Direction::arrivalRouteId)
                .containsOnlyNulls();
    }

    private static TransitPatternPort.NearStop near(TransitStop stop) {
        return new TransitPatternPort.NearStop(stop, null, List.of());
    }
}
