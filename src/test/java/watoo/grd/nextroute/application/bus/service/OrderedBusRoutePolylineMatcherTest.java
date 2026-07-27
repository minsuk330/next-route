package watoo.grd.nextroute.application.bus.service;

import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.bus.dto.BusRoutePolylineData;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineData;
import watoo.grd.nextroute.application.bus.dto.BusRouteStopMatchData;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderedBusRoutePolylineMatcherTest {

	private static final OffsetDateTime FETCHED_AT = OffsetDateTime.parse("2026-07-23T00:00:00Z");
	private final OrderedBusRoutePolylineMatcher matcher = new OrderedBusRoutePolylineMatcher();

	@Test
	void TC_직선_노선을_정류장_순서대로_잘라_OK로_판정한다() {
		BusRoutePolylineData route = line(
				point(126.9000, 37.5000),
				point(126.9050, 37.5000),
				point(126.9100, 37.5000));
		List<BusRouteStopMatchData> stops = List.of(
				stop(1, "A", "0", 0.0, 126.9000, 37.5000),
				stop(2, "B", "S1", 440.0, 126.9050, 37.5000),
				stop(3, "C", "S2", 440.0, 126.9100, 37.5000));

		List<BusRouteSegmentPolylineData> result = matcher.match("R", route, stops, FETCHED_AT);

		assertThat(result).hasSize(2).allMatch(segment ->
				segment.qualityStatus() == BusRouteSegmentPolylineData.QualityStatus.OK);
		assertThat(result.get(0).points().get(result.get(0).points().size() - 1))
				.isEqualTo(result.get(1).points().get(0));
		assertThat(result.get(0).endFraction()).isEqualTo(result.get(1).startFraction());
	}

	@Test
	void TC_같은_도로를_왕복해도_투영_위치가_뒤로만_진행한다() {
		BusRoutePolylineData route = line(
				point(126.9000, 37.5000),
				point(126.9040, 37.5000),
				point(126.9080, 37.5000),
				point(126.9040, 37.5000),
				point(126.9000, 37.5000));
		List<BusRouteStopMatchData> stops = List.of(
				stop(1, "A-out", "0", 0.0, 126.9000, 37.5000),
				stop(2, "B-out", "S1", 352.0, 126.9040, 37.5000),
				stop(3, "TURN", "S2", 352.0, 126.9080, 37.5000),
				stop(4, "B-in", "S3", 352.0, 126.9040, 37.5000),
				stop(5, "A-in", "S4", 352.0, 126.9000, 37.5000));

		List<BusRouteSegmentPolylineData> result = matcher.match("R", route, stops, FETCHED_AT);

		assertThat(result).hasSize(4);
		for (int index = 1; index < result.size(); index++) {
			assertThat(result.get(index).startFraction()).isEqualTo(result.get(index - 1).endFraction());
			assertThat(result.get(index).endFraction()).isGreaterThan(result.get(index).startFraction());
		}
		assertThat(result.get(3).endFraction()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
	}

	@Test
	void TC_정류장_투영오차가_20m를_넘으면_REVIEW로_저장한다() {
		BusRoutePolylineData route = line(point(126.9000, 37.5000), point(126.9100, 37.5000));
		List<BusRouteStopMatchData> stops = List.of(
				stop(1, "A", "0", 0.0, 126.9000, 37.5003),
				stop(2, "B", "S1", 880.0, 126.9100, 37.5003));

		BusRouteSegmentPolylineData result = matcher.match("R", route, stops, FETCHED_AT).get(0);

		assertThat(result.qualityStatus()).isEqualTo(BusRouteSegmentPolylineData.QualityStatus.REVIEW);
		assertThat(result.qualityReason()).contains("STOP_ERROR_GT_20M");
	}

	@Test
	void TC_좌표가_없는_정류장이_포함된_구간은_FAILED다() {
		BusRoutePolylineData route = line(point(126.9000, 37.5000), point(126.9100, 37.5000));
		List<BusRouteStopMatchData> stops = List.of(
				stop(1, "A", "0", 0.0, null, null),
				stop(2, "B", "S1", 880.0, 126.9100, 37.5000));

		BusRouteSegmentPolylineData result = matcher.match("R", route, stops, FETCHED_AT).get(0);

		assertThat(result.qualityStatus()).isEqualTo(BusRouteSegmentPolylineData.QualityStatus.FAILED);
		assertThat(result.points()).isEmpty();
	}

	private BusRoutePolylineData line(BusRoutePolylineData.Point... points) {
		return new BusRoutePolylineData(List.of(points));
	}

	private BusRoutePolylineData.Point point(double longitude, double latitude) {
		return new BusRoutePolylineData.Point(longitude, latitude);
	}

	private BusRouteStopMatchData stop(
			int seq, String stopId, String sectionId, Double distance, Double longitude, Double latitude
	) {
		return new BusRouteStopMatchData(seq, stopId, sectionId, distance, longitude, latitude);
	}
}
