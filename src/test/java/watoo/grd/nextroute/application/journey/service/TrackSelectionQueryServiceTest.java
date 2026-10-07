package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyTrackProperties;
import watoo.grd.nextroute.application.journey.dto.TrackViews;
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;
import watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternDetail;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.routing.EncodedPolyline;
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
    @DisplayName("노선 선(to 없음): 하차 후보 마지막(한 바퀴 끝)까지, 시작 후 안내와 같은 slice")
    void shapeWholeLap() {
        TrackViews.Shape shape = service.shape("1:RR_ACC1_S-1-02-1I", TrackFixtures.GANGNAM, null, null);

        assertThat(shape.quality()).isEqualTo(TrackViews.ShapeQuality.SHAPE_OK);
        assertThat(shape.points()).isEqualTo(
                EncodedPolyline.decode(TrackLegFactory.geometry(line2, 31, 73, "g").displayPoints()));
        assertThat(near(shape.points().get(0), line2.stops().get(31))).isTrue();
        assertThat(near(shape.points().get(shape.points().size() - 1), line2.stops().get(73))).isTrue();
        List<TrackViews.AlightStop> alight = service.alightStops("1:RR_ACC1_S-1-02-1I", TrackFixtures.GANGNAM, null);
        assertThat(alight.get(alight.size() - 1).gtfsStopId()).isEqualTo(line2.stops().get(73).gtfsId());
    }

    @Test
    @DisplayName("노선 선(to 있음): 시작과 같은 승차~하차 slice")
    void shapeToAlight() {
        String to = line2.stops().get(35).gtfsId();

        TrackViews.Shape shape = service.shape("1:RR_ACC1_S-1-02-1I", TrackFixtures.GANGNAM, to,
                TrackFixtures.LINE2_PATTERN);

        assertThat(shape.quality()).isEqualTo(TrackViews.ShapeQuality.SHAPE_OK);
        assertThat(shape.points()).isEqualTo(
                EncodedPolyline.decode(TrackLegFactory.geometry(line2, 31, 35, "g").displayPoints()));
    }

    @Test
    @DisplayName("형상이 없으면 승차~하차 정류장 직선, STOP_LINE")
    void shapeStopLine() {
        TransitPatternDetail noShape = new TransitPatternDetail(line2.code(), line2.route(), line2.stops(), null);
        given(port.pattern(eq(TrackFixtures.LINE2_PATTERN), any())).willReturn(Optional.of(noShape));

        TrackViews.Shape shape = service.shape("1:RR_ACC1_S-1-02-1I", TrackFixtures.GANGNAM,
                line2.stops().get(35).gtfsId(), null);

        assertThat(shape.quality()).isEqualTo(TrackViews.ShapeQuality.STOP_LINE);
        assertThat(shape.points()).hasSize(5);
        assertThat(shape.points().get(0)).isEqualTo(new CoordPoint(line2.stops().get(31).lon(),
                line2.stops().get(31).lat()));
    }

    @Test
    @DisplayName("노선 선: 그 노선으로 못 가는 하차면 422, 상세가 정류장 패턴과 어긋나면 TRACK_PATTERN_UNAVAILABLE")
    void shapeErrors() {
        assertThatThrownBy(() -> service.shape("1:RR_ACC1_S-1-02-1I", TrackFixtures.GANGNAM, "1:NOWHERE", null))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRACK_STOPS_INVALID);
        assertThatThrownBy(() -> service.shape("1:OTHER", TrackFixtures.GANGNAM, null, null))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRACK_STOPS_INVALID);

        TransitPatternDetail shorter = new TransitPatternDetail(line2.code(), line2.route(),
                line2.stops().subList(0, 80), line2.geometryPoints());
        given(port.pattern(eq(TrackFixtures.LINE2_PATTERN), any())).willReturn(Optional.of(shorter));
        assertThatThrownBy(() -> service.shape("1:RR_ACC1_S-1-02-1I", TrackFixtures.GANGNAM, null, null))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRACK_PATTERN_UNAVAILABLE);
    }

    private static boolean near(CoordPoint point, TransitStop stop) {
        return Math.hypot(point.x() - stop.lon(), point.y() - stop.lat()) < 0.003;
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
