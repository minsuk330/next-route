package watoo.grd.nextroute.infrastructure.adapter.out.persistence.bus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.bus.service.BusRoutePolylineData;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineData;
import watoo.grd.nextroute.application.bus.port.out.BusRouteSegmentPolylinePort;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
		"collector.static-data.enabled=false",
		"realtime.subway.enabled=false",
		"kma.weather.enabled=false"
})
class PostgisBusRouteSegmentPolylineAdapterIT {

	private static final String ROUTE_ID = "segment-polyline-it-route";
	private static final OffsetDateTime FETCHED_AT = OffsetDateTime.parse("2026-07-23T00:00:00Z");

	@Autowired BusRouteSegmentPolylinePort port;
	@Autowired JdbcTemplate jdbcTemplate;

	@Test
	void TC_연속_구간을_PostGIS에_저장하고_중복_접점_없이_하나의_path로_조회한다() {
		jdbcTemplate.update("INSERT INTO bus_route (route_id, route_name) VALUES (?, ?)", ROUTE_ID, "IT");
		BusRoutePolylineData.Point a = point(126.90, 37.50);
		BusRoutePolylineData.Point b = point(126.91, 37.50);
		BusRoutePolylineData.Point c = point(126.92, 37.50);

		port.replaceRouteSegments(ROUTE_ID, List.of(
				segment(1, 2, "S1", List.of(a, b), BusRouteSegmentPolylineData.QualityStatus.OK),
				segment(2, 3, "S2", List.of(b, c), BusRouteSegmentPolylineData.QualityStatus.OK)));

		var result = port.findRange(ROUTE_ID, 1, 3).orElseThrow();
		assertThat(result.hasGap()).isFalse();
		assertThat(result.points()).containsExactly(a, b, c);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM bus_route_segment_polyline WHERE route_id = ? AND geom IS NOT NULL",
				Integer.class, ROUTE_ID)).isEqualTo(2);
	}

	@Test
	void TC_REVIEW_구간이_포함되면_점을_직선으로_연결하지_않는다() {
		jdbcTemplate.update("INSERT INTO bus_route (route_id, route_name) VALUES (?, ?)", ROUTE_ID, "IT");
		BusRoutePolylineData.Point a = point(126.90, 37.50);
		BusRoutePolylineData.Point b = point(126.91, 37.50);
		BusRoutePolylineData.Point c = point(126.92, 37.50);

		port.replaceRouteSegments(ROUTE_ID, List.of(
				segment(1, 2, "S1", List.of(a, b), BusRouteSegmentPolylineData.QualityStatus.OK),
				segment(2, 3, "S2", List.of(b, c), BusRouteSegmentPolylineData.QualityStatus.REVIEW)));

		var result = port.findRange(ROUTE_ID, 1, 3).orElseThrow();
		assertThat(result.hasGap()).isTrue();
		assertThat(result.points()).isEmpty();
	}

	private BusRouteSegmentPolylineData segment(
			int fromSeq,
			int toSeq,
			String sectionId,
			List<BusRoutePolylineData.Point> points,
			BusRouteSegmentPolylineData.QualityStatus quality
	) {
		return new BusRouteSegmentPolylineData(
				ROUTE_ID, fromSeq, toSeq, "stop-" + fromSeq, "stop-" + toSeq, sectionId, points,
				(fromSeq - 1) / 2.0, (toSeq - 1) / 2.0,
				0.0, 0.0, 880.0, 880.0, 0.0,
				quality, quality == BusRouteSegmentPolylineData.QualityStatus.OK ? null : "TEST_REVIEW",
				"ordered-v1", FETCHED_AT);
	}

	private BusRoutePolylineData.Point point(double longitude, double latitude) {
		return new BusRoutePolylineData.Point(longitude, latitude);
	}
}
