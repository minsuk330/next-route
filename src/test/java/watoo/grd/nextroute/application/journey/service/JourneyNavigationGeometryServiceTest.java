package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.bus.dto.BusRoutePolylineData;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineData.QualityStatus;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineSection;
import watoo.grd.nextroute.application.bus.port.out.BusRouteSegmentPolylinePort;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeometryQuality;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyEndReason;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.bus.repository.RouteStopProjection;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("여정 좌표를 단계 종류별 원천에서 모은다")
class JourneyNavigationGeometryServiceTest {

    private static final long JOURNEY_ID = 7L;
    private static final long USER_ID = 42L;
    private static final int REVISION = 1;

    private JourneySessionRepository sessionRepository;
    private JourneyLegRepository legRepository;
    private JourneyLegRouteOptionRepository routeOptionRepository;
    private BusRouteStopRepository busRouteStopRepository;
    private BusRouteSegmentPolylinePort busPolylinePort;
    private JourneyNavigationGeometryService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        sessionRepository = mock(JourneySessionRepository.class);
        legRepository = mock(JourneyLegRepository.class);
        routeOptionRepository = mock(JourneyLegRouteOptionRepository.class);
        busRouteStopRepository = mock(BusRouteStopRepository.class);
        busPolylinePort = mock(BusRouteSegmentPolylinePort.class);
        service = new JourneyNavigationGeometryService(
                sessionRepository, legRepository, routeOptionRepository,
                busRouteStopRepository, busPolylinePort,
                new JourneyCompiler(new StopIdentityResolver()),
                new GeometrySegmentAllocator(),
                objectMapper);
    }

    @Test
    @DisplayName("버스 구간은 합치지 않고 구간 단위로 내려주고 불량 구간도 빼지 않는다")
    void keepsBusSectionsIncludingBadOnes() {
        givenJourney(walkThenBus());
        JourneyLeg wait = leg(1, JourneyLegType.WAIT_BUS, null);
        JourneyLeg ride = leg(2, JourneyLegType.RIDE_BUS, 1200);
        givenLegs(leg(0, JourneyLegType.WALK, 300), wait, ride);
        givenOptions(option(wait.getId(), "R1", 10, 13));
        when(busRouteStopRepository.findStopsByRouteId("R1")).thenReturn(List.of(
                routeStop(9, "S9", "범위 밖", 127.000, 37.5),
                routeStop(10, "S10", "승차", 127.001, 37.5),
                routeStop(11, "S11", "두 번째", 127.002, 37.5),
                routeStop(12, "S12", "세 번째", null, null),
                routeStop(13, "S13", "하차", 127.004, 37.5),
                routeStop(14, "S14", "범위 밖", 127.005, 37.5)));

        when(busPolylinePort.findSections("R1", 10, 13)).thenReturn(List.of(
                section(10, 11, "s1", QualityStatus.OK, 127.001, 127.002),
                section(11, 12, "s2", QualityStatus.REVIEW, 127.002, 127.003),
                new BusRouteSegmentPolylineSection(12, 13, "s3", QualityStatus.FAILED,
                        "NO_MATCH", List.of(), null)));

        JourneyNavigationGeometry result = service.get(USER_ID, JOURNEY_ID, REVISION);
        LegGeometry busLeg = result.legs().get(2);

        assertThat(busLeg.segments()).hasSize(3);
        assertThat(busLeg.segments()).extracting(s -> s.sectionId())
                .containsExactly("s1", "s2", "s3");
        assertThat(busLeg.segments()).extracting(s -> s.quality())
                .containsExactly(GeometryQuality.OK, GeometryQuality.REVIEW, GeometryQuality.FAILED);
        // 단계 품질은 가장 나쁜 구간을 따른다.
        assertThat(busLeg.quality()).isEqualTo(GeometryQuality.FAILED);
        assertThat(busLeg.routeId()).isEqualTo("R1");
        assertThat(busLeg.stops()).extracting(stop -> stop.seq())
                .containsExactly(10, 11, 12, 13);
        assertThat(busLeg.stops()).extracting(stop -> stop.stopName())
                .containsExactly("승차", "두 번째", "세 번째", "하차");
        assertThat(busLeg.stops().get(2).x()).isNull();
        assertThat(busLeg.stops().get(2).y()).isNull();
    }

    @Test
    @DisplayName("노선·정류장 순번을 해석하지 못한 버스 단계는 실시간 조회를 하지 않는다")
    void skipsLookupWhenBusScopeUnresolved() {
        givenJourney(walkThenBus());
        JourneyLeg wait = leg(1, JourneyLegType.WAIT_BUS, null);
        givenLegs(leg(0, JourneyLegType.WALK, 300), wait, leg(2, JourneyLegType.RIDE_BUS, 1200));
        // 순번 미해석 후보
        givenOptions(option(wait.getId(), "R1", null, null));

        service.get(USER_ID, JOURNEY_ID, REVISION);

        verify(busPolylinePort, never()).findSections(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("검색 결과 좌표가 있으면 걷기·지하철은 그 좌표로 구간을 만든다")
    void usesSnapshotPolylineForWalk() {
        givenJourney(walkWithPolyline());
        givenLegs(leg(0, JourneyLegType.WALK, 300));
        givenOptions();

        LegGeometry walk = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        assertThat(walk.quality()).isEqualTo(GeometryQuality.OK);
        assertThat(walk.points()).hasSize(3);
        assertThat(walk.segments()).hasSize(2);
        assertThat(walk.stops()).isEmpty();
        assertThat(walk.totalDistanceMeters()).isGreaterThan(0.0);
        assertThat(walk.segments().stream()
                .mapToInt(s -> s.plannedDurationSeconds())
                .sum()).isEqualTo(300);
    }

    @Test
    @DisplayName("좌표가 없으면 직선으로 채우고 품질을 APPROXIMATE 로 낮춘다")
    void fallsBackToStraightLine() {
        // TMAP 보강을 건너뛴 검색 결과. polyline 이 비어 있다.
        givenJourney(walkThenBus());
        givenLegs(leg(0, JourneyLegType.WALK, 300));
        givenOptions();

        LegGeometry walk = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        assertThat(walk.quality()).isEqualTo(GeometryQuality.APPROXIMATE);
        assertThat(walk.points()).hasSize(2);
        assertThat(walk.segments()).hasSize(1);
        assertThat(walk.segments().get(0).qualityReason()).isEqualTo("NO_SOURCE_GEOMETRY");
        assertThat(walk.segments().get(0).plannedDurationSeconds()).isEqualTo(300);
    }

    @Test
    @DisplayName("기다리는 단계는 한 지점만 주고 진행 판정 구간을 만들지 않는다")
    void waitLegHasNoSegments() {
        givenJourney(walkThenBus());
        givenLegs(leg(0, JourneyLegType.WALK, 300), leg(1, JourneyLegType.WAIT_BUS, null));
        givenOptions();

        LegGeometry wait = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(1);

        assertThat(wait.segments()).isEmpty();
        assertThat(wait.points()).hasSize(1);
        assertThat(wait.stops()).isEmpty();
    }

    @Test
    @DisplayName("같은 노선의 버스 단계가 여러 개여도 정류장은 요청당 한 번만 조회한다")
    void cachesRouteStopsWithinRequest() {
        givenJourney(walkThenBus());
        JourneyLeg firstWait = leg(0, JourneyLegType.WAIT_BUS, null);
        JourneyLeg firstRide = leg(1, JourneyLegType.RIDE_BUS, 600);
        JourneyLeg secondWait = leg(2, JourneyLegType.WAIT_BUS, null);
        JourneyLeg secondRide = leg(3, JourneyLegType.RIDE_BUS, 600);
        givenLegs(firstWait, firstRide, secondWait, secondRide);
        givenOptions(
                option(firstWait.getId(), "R1", 10, 11),
                option(secondWait.getId(), "R1", 11, 12));
        when(busRouteStopRepository.findStopsByRouteId("R1")).thenReturn(List.of(
                routeStop(10, "S10", "첫 정류장", 127.001, 37.5),
                routeStop(11, "S11", "둘째 정류장", 127.002, 37.5),
                routeStop(12, "S12", "셋째 정류장", 127.003, 37.5)));
        when(busPolylinePort.findSections("R1", 10, 11)).thenReturn(List.of(
                section(10, 11, "s1", QualityStatus.OK, 127.001, 127.002)));
        when(busPolylinePort.findSections("R1", 11, 12)).thenReturn(List.of(
                section(11, 12, "s2", QualityStatus.OK, 127.002, 127.003)));

        JourneyNavigationGeometry result = service.get(USER_ID, JOURNEY_ID, REVISION);

        assertThat(result.legs().get(1).stops()).hasSize(2);
        assertThat(result.legs().get(3).stops()).hasSize(2);
        verify(busRouteStopRepository, times(1)).findStopsByRouteId("R1");
    }

    @Test
    @DisplayName("이전 개정본을 가리키면 현재 개정본과 함께 충돌로 거절한다")
    void rejectsStaleRevision() {
        givenJourney(walkThenBus());

        assertThatThrownBy(() -> service.get(USER_ID, JOURNEY_ID, REVISION + 1))
                .isInstanceOf(StaleRouteRevisionException.class)
                .satisfies(e -> assertThat(((StaleRouteRevisionException) e).getCurrentRouteRevision())
                        .isEqualTo(REVISION));
    }

    @Test
    @DisplayName("개정본을 생략하면 현재 개정본을 준다")
    void defaultsToCurrentRevision() {
        givenJourney(walkThenBus());
        givenLegs(leg(0, JourneyLegType.WALK, 300));
        givenOptions();

        assertThat(service.get(USER_ID, JOURNEY_ID, null).routeRevision()).isEqualTo(REVISION);
    }

    @Test
    @DisplayName("route snapshot 이 깨져도 예외를 던지지 않고 직선으로 내려간다")
    void survivesBrokenSnapshot() {
        JourneySession journey = session();
        ReflectionTestUtils.setField(journey, "routeSnapshot",
                objectMapper.createObjectNode().put("깨진", "모양"));
        when(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(JOURNEY_ID, USER_ID))
                .thenReturn(Optional.of(journey));
        givenLegs(leg(0, JourneyLegType.WALK, 300));
        givenOptions();

        LegGeometry walk = service.get(USER_ID, JOURNEY_ID, REVISION).legs().get(0);

        // 원본 구간을 못 찾으니 양 끝 좌표도 없다. 진행은 막지 않고 품질만 알린다.
        assertThat(walk.quality()).isEqualTo(GeometryQuality.FAILED);
        assertThat(walk.segments()).isEmpty();
    }

    @Test
    @DisplayName("없는 여정과 남의 여정을 구분하지 않는다")
    void hidesOwnership() {
        when(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER_ID, JOURNEY_ID, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(JourneyErrorCode.JOURNEY_NOT_FOUND));
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private void givenJourney(PathResult path) {
        JourneySession journey = session();
        ReflectionTestUtils.setField(journey, "routeSnapshot", objectMapper.valueToTree(path));
        when(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(JOURNEY_ID, USER_ID))
                .thenReturn(Optional.of(journey));
    }

    private JourneySession session() {
        JourneySession journey = JourneySession.builder()
                .userId(USER_ID)
                .startedAt(Instant.parse("2026-08-04T00:00:00Z"))
                .guidanceLeaseUntil(Instant.parse("2026-08-04T00:03:00Z"))
                .build();
        ReflectionTestUtils.setField(journey, "id", JOURNEY_ID);
        return journey;
    }

    private void givenLegs(JourneyLeg... legs) {
        when(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                JOURNEY_ID, REVISION)).thenReturn(List.of(legs));
    }

    private void givenOptions(JourneyLegRouteOption... options) {
        when(routeOptionRepository.findByJourneyLegIdInAndDeletedAtIsNull(any()))
                .thenReturn(List.of(options));
    }

    private JourneyLeg leg(int index, JourneyLegType type, Integer plannedSeconds) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(JOURNEY_ID)
                .routeRevision(REVISION)
                .legIndex(index)
                .type(type)
                .state(index == 0 ? JourneyLegState.ACTIVE : JourneyLegState.PENDING)
                .plannedDurationSec(plannedSeconds)
                .build();
        ReflectionTestUtils.setField(leg, "id", 100L + index);
        return leg;
    }

    private JourneyLegRouteOption option(Long legId, String routeId,
                                         Integer boardingSeq, Integer alightingSeq) {
        JourneyLegRouteOption option = JourneyLegRouteOption.builder()
                .journeyLegId(legId)
                .optionIndex(0)
                .laneIndex(0)
                .routeId(routeId)
                .sourceRouteId("BL" + routeId)
                .routeName("370")
                .boardingSeq(boardingSeq)
                .alightingSeq(alightingSeq)
                .build();
        ReflectionTestUtils.setField(option, "id", 900L);
        return option;
    }

    private BusRouteSegmentPolylineSection section(int fromSeq, int toSeq, String sectionId,
                                                  QualityStatus quality, double startX, double endX) {
        return new BusRouteSegmentPolylineSection(fromSeq, toSeq, sectionId, quality, null,
                List.of(new BusRoutePolylineData.Point(startX, 37.5),
                        new BusRoutePolylineData.Point(endX, 37.5)),
                OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    private RouteStopProjection routeStop(int seq, String stopId, String stopName,
                                          Double longitude, Double latitude) {
        return new RouteStopProjection() {
            @Override public Integer getSeq() { return seq; }
            @Override public String getStopId() { return stopId; }
            @Override public String getStopName() { return stopName; }
            @Override public Double getLongitude() { return longitude; }
            @Override public Double getLatitude() { return latitude; }
            @Override public String getDirection() { return null; }
        };
    }

    /** 걷기 → 버스. 좌표 보강이 없어 polyline 이 비어 있는 경로다. */
    private PathResult walkThenBus() {
        SubPathResult walk = SubPathFixture.walk(5).names("집", "정류장")
                .coords(127.0, 37.5, 127.001, 37.5).build();
        SubPathResult bus = SubPathFixture.bus(20).names("정류장", "회사앞")
                .coords(127.001, 37.5, 127.02, 37.5)
                .busStops("100", "200")
                .lanes(SubPathFixture.busLane("370", "BL370"))
                .build();
        return new PathResult(1, null, List.of(walk, bus), List.of());
    }

    /** TMAP 보강이 붙은 걷기 하나. */
    private PathResult walkWithPolyline() {
        SubPathResult walk = SubPathFixture.walk(5).names("집", "정류장")
                .coords(127.0, 37.5, 127.002, 37.5)
                .polyline(new CoordPoint(127.0, 37.5),
                        new CoordPoint(127.001, 37.5),
                        new CoordPoint(127.002, 37.5))
                .build();
        return new PathResult(1, null, List.of(walk), List.of());
    }
}
