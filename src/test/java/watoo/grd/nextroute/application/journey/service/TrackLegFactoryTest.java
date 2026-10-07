package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternDetail;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStopTime;
import watoo.grd.nextroute.application.route.routing.EncodedPolyline;
import watoo.grd.nextroute.application.route.routing.GeometryQuality;
import watoo.grd.nextroute.application.route.routing.MissingAttribute;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("단독 추적 구간 합성")
class TrackLegFactoryTest {

    private static final LocalDate DAY = LocalDate.parse("2026-10-07");

    @Test
    @DisplayName("2호선 강남 → 4역: 승하차 포함 정류장, 시간표 소요, 방향 라벨, 잘린 형상")
    void subwaySegment() {
        TransitPatternDetail detail = TrackFixtures.line2Detail();
        List<TransitStopTime> times = TrackFixtures.line2Times();

        RoutingLeg leg = TrackLegFactory.build(detail, 31, 35, times, "trip-1", DAY, "g1");

        assertThat(leg.mode()).isEqualTo(RoutingMode.SUBWAY);
        assertThat(leg.sourceDetailType()).isEqualTo("SUBWAY");
        assertThat(leg.feedId()).isEqualTo("1");
        assertThat(leg.sourceRouteId()).isEqualTo("1:RR_ACC1_S-1-02-1I");
        assertThat(leg.sourceRouteName()).isEqualTo("서울2호선");
        assertThat(leg.sourceRouteDirection()).isEqualTo("내선");
        assertThat(leg.sourceRouteColor()).isEqualTo("00A23F");
        assertThat(leg.stops()).hasSize(5);
        assertThat(leg.stops().get(0).sourceStopId()).isEqualTo(TrackFixtures.GANGNAM);
        assertThat(leg.stops()).extracting(StopOccurrence::occurrenceIndex).containsExactly(0, 1, 2, 3, 4);
        assertThat(leg.rideDurationSeconds())
                .isEqualTo(times.get(35).scheduledArrival() - times.get(31).scheduledDeparture())
                .isPositive();
        assertThat(leg.missing()).containsExactly(MissingAttribute.REALTIME_ID);
        assertThat(leg.geometry().quality()).isEqualTo(GeometryQuality.SHAPE_OK);
    }

    @Test
    @DisplayName("형상: 같은 역을 두 번 지나는 패턴에서도 고른 바퀴의 구간만 자른다")
    void geometrySliceStaysOnChosenLap() {
        TransitPatternDetail detail = TrackFixtures.line2Detail();
        List<CoordPoint> full = EncodedPolyline.decode(detail.geometryPoints());

        RoutingLeg first = TrackLegFactory.build(detail, 31, 35, TrackFixtures.line2Times(), "t", DAY, "g1");
        List<CoordPoint> slice = EncodedPolyline.decode(first.geometry().displayPoints());

        assertThat(slice.size()).isGreaterThan(2).isLessThan(full.size() / 4);
        assertThat(distanceDeg(slice.get(0), detail.stops().get(31))).isLessThan(0.003);
        assertThat(distanceDeg(slice.get(slice.size() - 1), detail.stops().get(35))).isLessThan(0.003);
    }

    @Test
    @DisplayName("인코딩은 디코더와 왕복한다(precision 5)")
    void encodeRoundTrip() {
        List<CoordPoint> points = List.of(new CoordPoint(127.02761, 37.49794),
                new CoordPoint(127.03612, 37.50083), new CoordPoint(126.99, 37.51));

        List<CoordPoint> back = EncodedPolyline.decode(TrackLegFactory.encode(points));

        assertThat(back).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(back.get(i).x()).isCloseTo(points.get(i).x(), org.assertj.core.data.Offset.offset(1e-5));
            assertThat(back.get(i).y()).isCloseTo(points.get(i).y(), org.assertj.core.data.Offset.offset(1e-5));
        }
    }

    @Test
    @DisplayName("버스(TAGO 서울): 방향 라벨 없음 → DIRECTION 누락 표기")
    void busSegment() {
        TransitPatternDetail detail = TrackFixtures.bus640Detail();

        RoutingLeg leg = TrackLegFactory.build(detail, 10, 20, TrackFixtures.bus640Times(), "t", DAY, "g1");

        assertThat(leg.mode()).isEqualTo(RoutingMode.BUS);
        assertThat(leg.sourceRouteDirection()).isNull();
        assertThat(leg.missing()).contains(MissingAttribute.REALTIME_ID, MissingAttribute.DIRECTION);
        assertThat(leg.stops()).hasSize(11);
    }

    @Test
    @DisplayName("정차 시각이 패턴과 어긋나면 실패 — 근사하지 않는다")
    void mismatchedTimesFail() {
        TransitPatternDetail detail = TrackFixtures.line2Detail();
        List<TransitStopTime> shorter = new ArrayList<>(TrackFixtures.line2Times());
        shorter.remove(shorter.size() - 1);

        assertThatThrownBy(() -> TrackLegFactory.build(detail, 31, 35, shorter, "t", DAY, "g1"))
                .isInstanceOf(TrackLegFactory.ScheduleUnavailableException.class);
        assertThatThrownBy(() -> TrackLegFactory.build(detail, 31, 35, List.of(), "t", DAY, "g1"))
                .isInstanceOf(TrackLegFactory.ScheduleUnavailableException.class);
    }

    @Test
    @DisplayName("소요가 0 이하면 실패")
    void nonPositiveDurationFails() {
        TransitPatternDetail detail = TrackFixtures.line2Detail();
        List<TransitStopTime> flat = TrackFixtures.line2Times().stream()
                .map(t -> new TransitStopTime(t.stopGtfsId(), 1000, 1000)).toList();

        assertThatThrownBy(() -> TrackLegFactory.build(detail, 31, 35, flat, "t", DAY, "g1"))
                .isInstanceOf(TrackLegFactory.ScheduleUnavailableException.class);
    }

    @Test
    @DisplayName("형상이 없으면 형상 없음(가짜 직선 금지), 구간은 그대로 만든다")
    void missingGeometry() {
        TransitPatternDetail detail = TrackFixtures.line2Detail();
        TransitPatternDetail noShape = new TransitPatternDetail(detail.code(), detail.route(), detail.stops(), null);

        RoutingLeg leg = TrackLegFactory.build(noShape, 31, 35, TrackFixtures.line2Times(), "t", DAY, "g1");

        assertThat(leg.geometry().quality()).isEqualTo(GeometryQuality.MISSING);
        assertThat(leg.stops()).hasSize(5);
    }

    private static double distanceDeg(CoordPoint point, TransitStop stop) {
        return Math.hypot(point.x() - stop.lon(), point.y() - stop.lat());
    }
}
