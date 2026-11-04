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
import watoo.grd.nextroute.infrastructure.adapter.out.api.otp.OtpTransitPatternAdapter;
import watoo.grd.nextroute.global.error.code.CommonErrorCode;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
                Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneOffset.UTC)), realtimeIds,
                new watoo.grd.nextroute.application.route.service.GyeonggiBusRouteTypeColors());
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
    @DisplayName("하차 후보: 내릴 수 없는 정류장은 빠지고 index 는 그대로다. 승하차 가능 여부는 패턴 단위로 캐시된다")
    void alightStopsSkipNoDropoff() {
        given(port.tripsForDate(eq(TrackFixtures.LINE2_PATTERN), any(), any())).willReturn(TrackFixtures.line2Trips());
        given(port.tripStopTimes(anyString(), any(), any()))
                .willReturn(TrackFixtures.line2TimesBlocking(Set.of(), Set.of(33)));

        List<TrackViews.AlightStop> stops = service.alightStops("1:RR_ACC1_S-1-02-1I", TrackFixtures.GANGNAM, null);
        service.alightStops("1:RR_ACC1_S-1-02-1I", TrackFixtures.GANGNAM, null);

        assertThat(stops).hasSize(74 - 31 - 1 - 1);
        assertThat(stops).noneMatch(s -> s.gtfsStopId().equals(line2.stops().get(33).gtfsId()));
        assertThat(stops.get(0).index()).isZero();
        assertThat(stops.get(1).index()).isEqualTo(2);
        verify(port, times(1)).tripsForDate(eq(TrackFixtures.LINE2_PATTERN), any(), any());
        verify(port, times(1)).tripStopTimes(anyString(), any(), any());
    }

    @Test
    @DisplayName("하차 후보: 그 정류장에서 탈 수 없는 노선이면 422")
    void alightStopsNoPickupAtBoard() {
        given(port.tripsForDate(eq(TrackFixtures.LINE2_PATTERN), any(), any())).willReturn(TrackFixtures.line2Trips());
        given(port.tripStopTimes(anyString(), any(), any()))
                .willReturn(TrackFixtures.line2TimesBlocking(Set.of(31), Set.of()));

        assertThatThrownBy(() -> service.alightStops("1:RR_ACC1_S-1-02-1I", TrackFixtures.GANGNAM, null))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRACK_STOPS_INVALID);
    }

    @Test
    @DisplayName("하차 후보: 승하차 가능 여부를 모르면(운행 없음) 거르지 않는다")
    void alightStopsWithoutTrips() {
        assertThat(service.alightStops("1:RR_ACC1_S-1-02-1I", TrackFixtures.GANGNAM, null)).hasSize(74 - 31 - 1);
        verify(port).tripsForDate(eq(TrackFixtures.LINE2_PATTERN), eq(java.time.LocalDate.parse("2026-10-07")), any());
        verify(port).tripsForDate(eq(TrackFixtures.LINE2_PATTERN), eq(java.time.LocalDate.parse("2026-10-06")), any());
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

    // ── 피드별로 쪼개진 같은 정류장(신분당선강남역 SEB/GGB/ICB 121000009, 2026-10-10 운영 OTP 실측) ──

    private static final String SEB = "1:BS_TAGO_SEB121000009";
    private static final String GGB = "1:BS_TAGO_GGB121000009";
    private static final String ICB = "1:BS_TAGO_ICB121000009";
    /** 경기 5100, 경기 stop 에만 선다. */
    private static final String GGB_5100 = "1:BR_TAGO_GGB200000115";

    private void givenSinbundangSiblings() {
        given(port.stops(any(), any())).willReturn(
                OtpTransitPatternAdapter.parseStopsByIds(TrackFixtures.json("stops-by-ids-sinbundang.json")));
        for (String id : List.of("SEB121000009", "GGB121000009", "ICB121000009")) {
            given(port.patternsAtStop(eq("1:BS_TAGO_" + id), any())).willReturn(OtpTransitPatternAdapter
                    .parsePatternsAtStop(TrackFixtures.json("patterns-at-stop-" + id + ".json")));
        }
        given(realtimeIds.busStop(SEB)).willReturn(new TrackRealtimeIdResolver.BusStopIds("121000009", "22010"));
        given(realtimeIds.busStop(GGB)).willReturn(new TrackRealtimeIdResolver.BusStopIds("121000009-GG", null));
    }

    @Test
    @DisplayName("#391 방향 색: 경기 직행좌석 5100 은 그래프 기본 초록이 아니라 노선 유형 빨강 — 검색 응답과 같은 규칙")
    void gyeonggiDirectionColorByRouteType() {
        givenSinbundangSiblings();

        assertThat(service.directions(SEB)).filteredOn(d -> "5100".equals(d.routeName()))
                .isNotEmpty()
                .allSatisfy(d -> assertThat(d.color()).isEqualTo("#E60012"));
    }

    @Test
    @DisplayName("방향: 서울 stop 을 골라도 경기·인천 stop 노선까지 전부, 형제 조회는 캐시된다")
    void directionsMergeSiblingFeeds() {
        givenSinbundangSiblings();

        List<TrackViews.Direction> directions = service.directions(SEB);
        service.directions(SEB);

        assertThat(directions).extracting(TrackViews.Direction::routeName).doesNotHaveDuplicates().isNotEmpty();
        // 세 stop 각각에서 탈 수 있는 노선의 합(20 + 35 + 1 중 이 stop 이 종점인 5100예약 은 못 탄다)
        List<String> boardable = List.of("SEB121000009", "GGB121000009", "ICB121000009").stream()
                .flatMap(id -> TrackPatternSelector.branches("1:BS_TAGO_" + id, OtpTransitPatternAdapter
                        .parsePatternsAtStop(TrackFixtures.json("patterns-at-stop-" + id + ".json"))).stream())
                .map(b -> b.pattern().route().shortName()).distinct().toList();
        assertThat(boardable).hasSize(55);
        assertThat(directions.stream().map(TrackViews.Direction::routeName).distinct().toList())
                .containsExactlyInAnyOrderElementsOf(boardable).contains("140", "5100", "M6458");
        assertThat(directions).extracting(TrackViews.Direction::routeName).isSorted();
        verify(port, times(1)).stops(eq(List.of(SEB, GGB, ICB)), any());
    }

    @Test
    @DisplayName("방향: 노선마다 실제로 타는 정류장과 그 정류장 기준 도착 조회 id — 경기 노선은 경기 stop 으로 물어야 나온다")
    void directionsCarryBoardStopAndArrivalStop() {
        givenSinbundangSiblings();

        List<TrackViews.Direction> directions = service.directions(SEB);

        assertThat(directions).filteredOn(d -> d.routeName().equals("5100")).allSatisfy(d -> {
            assertThat(d.boardStopGtfsId()).isEqualTo(GGB);
            assertThat(d.arrivalStopId()).isEqualTo("121000009-GG");
        });
        assertThat(directions).filteredOn(d -> d.routeName().equals("140")).allSatisfy(d -> {
            assertThat(d.boardStopGtfsId()).isEqualTo(SEB);
            assertThat(d.arrivalStopId()).isEqualTo("121000009");
        });
        assertThat(directions).filteredOn(d -> d.routeName().equals("M6458"))
                .extracting(TrackViews.Direction::boardStopGtfsId).containsOnly(ICB);
    }

    @Test
    @DisplayName("방향: 형제 stop 에 같은 노선 이름·방향이 또 있으면 고른 stop 쪽만 남긴다")
    void directionsPreferChosenStopOnDuplicate() {
        givenSinbundangSiblings();
        // 경기 stop 패턴을 그대로 서울 stop 에도 걸어 둔다 → 같은 노선·방향이 두 stop 에 다 있다
        List<TransitPatternPort.TransitPattern> ggbPatterns = OtpTransitPatternAdapter
                .parsePatternsAtStop(TrackFixtures.json("patterns-at-stop-GGB121000009.json"));
        given(port.patternsAtStop(eq(SEB), any())).willReturn(ggbPatterns.stream()
                .map(p -> new TransitPatternPort.TransitPattern(p.code(), p.headsign(), p.route(),
                        p.stops().stream().map(st -> st.gtfsId().equals(GGB)
                                ? new TransitPatternPort.PatternStop(SEB, st.name()) : st).toList(),
                        p.tripCount()))
                .toList());

        List<TrackViews.Direction> directions = service.directions(SEB);

        assertThat(directions).filteredOn(d -> d.routeName().equals("5100"))
                .isNotEmpty().extracting(TrackViews.Direction::boardStopGtfsId).containsOnly(SEB);
    }

    @Test
    @DisplayName("방향: 수도권 버스가 아니면(지하철) 형제를 찾지 않는다 — OTP 추가 호출 없음")
    void directionsNoSiblingLookupForSubway() {
        service.directions(TrackFixtures.GANGNAM);

        verify(port, times(0)).stops(any(), any());
    }

    @Test
    @DisplayName("하위 호환: 서울 stop id + 경기 노선을 넘겨도 경기 stop 에서 타는 것으로 하차 후보를 준다")
    void alightFromSiblingStop() {
        givenSinbundangSiblings();
        TransitPatternPort.TransitPattern p5100 = OtpTransitPatternAdapter
                .parsePatternsAtStop(TrackFixtures.json("patterns-at-stop-GGB121000009.json")).stream()
                .filter(p -> p.route().gtfsId().equals(GGB_5100)).findFirst().orElseThrow();
        TransitPatternDetail detail = new TransitPatternDetail(p5100.code(), p5100.route(),
                p5100.stops().stream().map(st -> new TransitStop(st.gtfsId(), st.name(), 37.5, 127.0,
                        RoutingMode.BUS)).toList(), null);
        given(port.pattern(eq(p5100.code()), any())).willReturn(Optional.of(detail));

        List<TrackViews.AlightStop> stops = service.alightStops(GGB_5100, SEB, null);
        TrackViews.Shape shape = service.shape(GGB_5100, SEB, null, null);

        assertThat(stops).isNotEmpty().noneMatch(s -> s.gtfsStopId().equals(GGB));
        assertThat(stops.get(0).gtfsStopId())
                .isEqualTo(p5100.stops().get(p5100.stops().indexOf(p5100.stops().stream()
                        .filter(st -> st.gtfsId().equals(GGB)).findFirst().orElseThrow()) + 1).gtfsId());
        assertThat(shape.points()).isNotEmpty();
    }

    @Test
    @DisplayName("하위 호환: 형제 어디에도 없는 노선이면 그대로 422")
    void alightRouteNowhereInSiblings() {
        givenSinbundangSiblings();

        assertThatThrownBy(() -> service.alightStops("1:BR_TAGO_GGB999", SEB, null))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRACK_STOPS_INVALID);
    }

    @Test
    @DisplayName("주변: 쪼개진 같은 정류장은 한 줄 — 대표는 서울, 거리는 가장 가까운 것, 자리는 처음 만난 곳")
    void stopsNearMergesSiblings() {
        TransitStop seb = new TransitStop(SEB, "신분당선강남역", 37.4958, 127.028999, RoutingMode.BUS);
        TransitStop ggb = new TransitStop(GGB, "신분당선강남역(중)", 37.495783, 127.028567, RoutingMode.BUS);
        TransitStop icb = new TransitStop(ICB, "신분당선강남역(중)", 37.495914, 127.028557, RoutingMode.BUS);
        TransitStop other = new TransitStop("1:BS_TAGO_SEB121000091", "강남역9번출구", 37.4978, 127.027,
                RoutingMode.BUS);
        given(port.stopsNear(anyDouble(), anyDouble(), anyInt(), anyInt(), any())).willReturn(List.of(
                new TransitPatternPort.NearStop(ggb, 40, List.of()),
                new TransitPatternPort.NearStop(other, 50, List.of()),
                new TransitPatternPort.NearStop(icb, 55, List.of()),
                new TransitPatternPort.NearStop(seb, 70, List.of())));
        given(realtimeIds.busStop(SEB)).willReturn(new TrackRealtimeIdResolver.BusStopIds("121000009", "22010"));

        List<TrackViews.Stop> stops = service.stopsNear(37.5, 127.0, RoutingMode.BUS);

        assertThat(stops).extracting(TrackViews.Stop::gtfsStopId).containsExactly(SEB, "1:BS_TAGO_SEB121000091");
        assertThat(stops.get(0)).extracting(TrackViews.Stop::name, TrackViews.Stop::lat,
                TrackViews.Stop::distanceMeters, TrackViews.Stop::arrivalStopId, TrackViews.Stop::arsId)
                .containsExactly("신분당선강남역", 37.4958, 40, "121000009", "22010");
    }

    @Test
    @DisplayName("주변: 대표(서울)가 서울 DB 에 없으면 형제(경기)의 도착 id 를 싣는다(#333)")
    void stopsNearFallsBackToSiblingArrivalId() {
        TransitStop seb = new TransitStop(SEB, "신분당선강남역", 37.4958, 127.028999, RoutingMode.BUS);
        TransitStop ggb = new TransitStop(GGB, "신분당선강남역(중)", 37.495783, 127.028567, RoutingMode.BUS);
        given(port.stopsNear(anyDouble(), anyDouble(), anyInt(), anyInt(), any())).willReturn(List.of(
                new TransitPatternPort.NearStop(ggb, 40, List.of()),
                new TransitPatternPort.NearStop(seb, 70, List.of())));
        given(realtimeIds.busStop(GGB)).willReturn(new TrackRealtimeIdResolver.BusStopIds("121000009", null));

        List<TrackViews.Stop> stops = service.stopsNear(37.5, 127.0, RoutingMode.BUS);

        assertThat(stops).singleElement().satisfies(stop -> {
            assertThat(stop.gtfsStopId()).isEqualTo(SEB);
            assertThat(stop.arrivalStopId()).isEqualTo("121000009");
        });
    }

    @Test
    @DisplayName("주변: 300m 넘게 떨어진 같은 번호·지방 피드의 같은 번호는 묶지 않는다")
    void stopsNearDoesNotMergeFarOrRegional() {
        TransitStop seb = new TransitStop("1:BS_TAGO_SEB104000010", "메리놀외방선교회", 37.5490, 127.0740,
                RoutingMode.BUS);
        TransitStop farGgb = new TransitStop("1:BS_TAGO_GGB104000010", "어린이대공원역2번출구", 37.5611, 127.0740,
                RoutingMode.BUS);
        TransitStop adb = new TransitStop("1:BS_TAGO_ADB354000001", "길안정류장", 36.4587, 128.8912, RoutingMode.BUS);
        TransitStop tsb = new TransitStop("1:BS_TAGO_TSB354000001", "월전리", 36.4588, 128.8912, RoutingMode.BUS);
        given(port.stopsNear(anyDouble(), anyDouble(), anyInt(), anyInt(), any())).willReturn(List.of(
                near(seb), near(farGgb), near(adb), near(tsb)));

        assertThat(service.stopsNear(37.5, 127.0, RoutingMode.BUS)).hasSize(4);
    }

    private static TransitPatternPort.NearStop near(TransitStop stop) {
        return new TransitPatternPort.NearStop(stop, null, List.of());
    }

    // ── #538 진행 방향 ──────────────────────────────────────────────────────────

    private static final TransitPatternPort.PatternStop EAST =
            new TransitPatternPort.PatternStop("1:BS_TAGO_SEB1", "역삼역", 37.5, 127.001);
    private static final TransitPatternPort.PatternStop EAST_FAR =
            new TransitPatternPort.PatternStop("1:BS_TAGO_SEB3", "선릉역", 37.5, 127.002);

    private static TransitPatternPort.TransitPattern busPattern(String code, String routeName, String boardId,
                                                               TransitPatternPort.PatternStop... next) {
        List<TransitPatternPort.PatternStop> stops = new java.util.ArrayList<>();
        stops.add(new TransitPatternPort.PatternStop(boardId, "승차", 37.5, 127.0));
        stops.addAll(List.of(next));
        return new TransitPatternPort.TransitPattern(code, null, new TransitPatternPort.TransitRoute(
                code.substring(0, code.indexOf("::")), routeName, "-", RoutingMode.BUS, "BUS", null, null), stops, 1);
    }

    @Test
    @DisplayName("#538 근처 버스: 형제 묶음 노선을 합쳐 heading 을 싣고, 노선 이름은 합집합이다")
    void stopsNearHeading() {
        TransitStop seb = new TransitStop("1:BS_TAGO_SEB121000091", "강남역9번출구", 37.5, 127.0, RoutingMode.BUS);
        TransitStop ggb = new TransitStop("1:BS_TAGO_GGB121000091", "강남역.강남역사거리", 37.5, 127.0, RoutingMode.BUS);
        given(port.stopsNear(anyDouble(), anyDouble(), anyInt(), anyInt(), any())).willReturn(List.of(
                new TransitPatternPort.NearStop(seb, 54, List.of("146", "740")),
                new TransitPatternPort.NearStop(ggb, 55, List.of("2000", "146"))));
        given(port.patternsAtStop(eq(seb.gtfsId()), any())).willReturn(List.of(
                busPattern("1:R146::01", "146", seb.gtfsId(), EAST, EAST_FAR)));
        given(port.patternsAtStop(eq(ggb.gtfsId()), any())).willReturn(List.of(
                busPattern("1:R2000::01", "2000", ggb.gtfsId(), EAST)));

        List<TrackViews.Stop> stops = service.stopsNear(37.5, 127.0, RoutingMode.BUS);

        assertThat(stops).singleElement().satisfies(stop -> {
            assertThat(stop.routeNames()).containsExactly("146", "740", "2000");
            assertThat(stop.heading().patternCodes()).containsExactly("1:R146::01", "1:R2000::01");
            assertThat(stop.heading().nextStopNames()).containsExactly("역삼역", "선릉역");
            assertThat(stop.heading().bearingDegrees()).isBetween(89.0, 91.0);
        });
    }

    private static TransitPatternPort.TransitRoute nearRoute(String gtfsId, String name, String color) {
        return new TransitPatternPort.TransitRoute(gtfsId, name, null, RoutingMode.UNSUPPORTED, null, color, null);
    }

    @Test
    @DisplayName("#542 근처 버스 routes: 형제 묶음 합집합, 이름 중복은 색 있는 쪽, 색 없으면 null, # 를 붙인다")
    void stopsNearRoutes() {
        TransitStop seb = new TransitStop("1:BS_TAGO_SEB121000091", "강남역9번출구", 37.5, 127.0, RoutingMode.BUS);
        TransitStop ggb = new TransitStop("1:BS_TAGO_GGB121000091", "강남역.강남역사거리", 37.5, 127.0, RoutingMode.BUS);
        given(port.stopsNear(anyDouble(), anyDouble(), anyInt(), anyInt(), any())).willReturn(List.of(
                new TransitPatternPort.NearStop(seb, 54, List.of("146", "서초09"), List.of(
                        nearRoute("1:R146", "146", null),
                        nearRoute("1:R146_D", "146", null),
                        nearRoute("1:R09", "서초09", null))),
                new TransitPatternPort.NearStop(ggb, 55, List.of("146", "740"), List.of(
                        nearRoute("1:G146", "146", "0068B7"),
                        nearRoute("1:R740", "740", "53B332")))));

        assertThat(service.stopsNear(37.5, 127.0, RoutingMode.BUS)).singleElement()
                .extracting(TrackViews.Stop::routes)
                .isEqualTo(List.of(new TrackViews.Route("146", "#0068B7"), new TrackViews.Route("서초09", null),
                        new TrackViews.Route("740", "#53B332")));
    }

    @Test
    @DisplayName("#542 근처 routes 의 색은 방향 목록 색과 같다 — 경기 노선은 노선 유형 색이다")
    void stopsNearRouteColorMatchesDirection() {
        TransitStop stop = new TransitStop("1:BS_TAGO_GGB1001", "가", 37.5, 127.0, RoutingMode.BUS);
        String routeId = "1:BR_TAGO_GGB200000115";
        given(port.stopsNear(anyDouble(), anyDouble(), anyInt(), anyInt(), any())).willReturn(List.of(
                new TransitPatternPort.NearStop(stop, 54, List.of("3100"),
                        List.of(nearRoute(routeId, "3100", "53B332")))));
        given(port.patternsAtStop(eq(stop.gtfsId()), any())).willReturn(List.of(
                new TransitPatternPort.TransitPattern(routeId + "::01", null, new TransitPatternPort.TransitRoute(
                        routeId, "3100", "-", RoutingMode.BUS, "BUS", "53B332", null),
                        List.of(new TransitPatternPort.PatternStop(stop.gtfsId(), "가"), EAST), 1)));

        String nearColor = service.stopsNear(37.5, 127.0, RoutingMode.BUS).get(0).routes().get(0).color();

        assertThat(nearColor).isEqualTo(new watoo.grd.nextroute.application.route.service.GyeonggiBusRouteTypeColors()
                .color(routeId).orElseThrow());
        assertThat(nearColor).isEqualTo(service.directions(stop.gtfsId()).get(0).color());
    }

    @Test
    @DisplayName("#538 headingLimit 밖 묶음은 heading null, OTP 를 부르지 않는다")
    void stopsNearHeadingLimit() {
        properties.setHeadingLimit(1);
        TransitStop first = new TransitStop("1:BS_TAGO_SEB1001", "가", 37.5, 127.0, RoutingMode.BUS);
        TransitStop second = new TransitStop("1:BS_TAGO_SEB1002", "나", 37.5, 127.0, RoutingMode.BUS);
        given(port.stopsNear(anyDouble(), anyDouble(), anyInt(), anyInt(), any())).willReturn(List.of(
                near(first), near(second)));
        given(port.patternsAtStop(eq(first.gtfsId()), any())).willReturn(List.of(
                busPattern("1:R1::01", "1", first.gtfsId(), EAST)));

        List<TrackViews.Stop> stops = service.stopsNear(37.5, 127.0, null);

        assertThat(stops.get(0).heading()).isNotNull();
        assertThat(stops.get(1).heading()).isNull();
        verify(port, times(0)).patternsAtStop(eq(second.gtfsId()), any());
    }

    @Test
    @DisplayName("#538 heading 예산을 다 쓰면 남은 정류장은 heading null, 목록은 그대로")
    void stopsNearHeadingBudget() {
        properties.setHeadingBudgetMs(0);
        TransitStop stop = new TransitStop("1:BS_TAGO_SEB1001", "가", 37.5, 127.0, RoutingMode.BUS);
        given(port.stopsNear(anyDouble(), anyDouble(), anyInt(), anyInt(), any())).willReturn(List.of(near(stop)));

        List<TrackViews.Stop> stops = service.stopsNear(37.5, 127.0, RoutingMode.BUS);

        assertThat(stops).singleElement().extracting(TrackViews.Stop::heading).isNull();
        verify(port, times(0)).patternsAtStop(any(), any());
    }

    @Test
    @DisplayName("#538 한 정류장 OTP 실패는 그 정류장만 heading null, 목록은 성공")
    void stopsNearHeadingFailureIsolated() {
        TransitStop broken = new TransitStop("1:BS_TAGO_SEB1001", "가", 37.5, 127.0, RoutingMode.BUS);
        TransitStop fine = new TransitStop("1:BS_TAGO_SEB1002", "나", 37.5, 127.0, RoutingMode.BUS);
        given(port.stopsNear(anyDouble(), anyDouble(), anyInt(), anyInt(), any())).willReturn(List.of(
                near(broken), near(fine)));
        given(port.patternsAtStop(eq(broken.gtfsId()), any()))
                .willThrow(new TransitPatternPort.TransitPatternUnavailableException("timeout"));
        given(port.patternsAtStop(eq(fine.gtfsId()), any())).willReturn(List.of(
                busPattern("1:R1::01", "1", fine.gtfsId(), EAST)));

        List<TrackViews.Stop> stops = service.stopsNear(37.5, 127.0, RoutingMode.BUS);

        assertThat(stops).hasSize(2);
        assertThat(stops.get(0).heading()).isNull();
        assertThat(stops.get(1).heading().patternCodes()).containsExactly("1:R1::01");
    }

    @Test
    @DisplayName("#538 지하철 줄은 heading null, 노선 이름은 그대로. SUBWAY 조회는 패턴을 부르지 않는다")
    void stopsNearSubwayHasNoHeading() {
        TransitStop subway = new TransitStop("1:RS_ACC1_S-1-X", "환승역", 37.5, 127.0, RoutingMode.SUBWAY);
        given(port.stopsNear(anyDouble(), anyDouble(), anyInt(), anyInt(), any())).willReturn(List.of(
                new TransitPatternPort.NearStop(subway, 90, List.of("서울2호선"))));

        List<TrackViews.Stop> stops = service.stopsNear(37.5, 127.0, RoutingMode.SUBWAY);

        assertThat(stops.get(0).heading()).isNull();
        assertThat(stops.get(0).routeNames()).containsExactly("서울2호선");
        assertThat(stops.get(0).routes()).isNull();
        verify(port, times(0)).patternsAtStop(any(), any());
    }

    @Test
    @DisplayName("#538 방향: nextStopNames 는 승차 다음 정류장 앞 2개")
    void directionNextStopNames() {
        assertThat(service.directions(TrackFixtures.GANGNAM)).singleElement()
                .extracting(TrackViews.Direction::nextStopNames)
                .isEqualTo(List.of(line2.stops().get(32).name(), line2.stops().get(33).name()));
    }
}
