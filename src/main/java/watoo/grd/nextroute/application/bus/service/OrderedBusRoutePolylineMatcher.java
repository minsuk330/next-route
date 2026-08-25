package watoo.grd.nextroute.application.bus.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.bus.service.BusRoutePolylineData;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineData;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopMatchData;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineData.QualityStatus.*;

@Component
public class OrderedBusRoutePolylineMatcher {

	static final String MATCHER_VERSION = "ordered-v1";
	static final double SEARCH_WINDOW_METERS = 1_500.0;
	static final double STRICT_STOP_ERROR_METERS = 20.0;
	static final double STRICT_DISTANCE_ERROR_METERS = 100.0;
	private static final double EXPECTED_POSITION_WEIGHT = 0.01;
	private static final double FALLBACK_PROJECTION_TRIGGER_METERS = 30.0;
	private static final double EARTH_RADIUS_METERS = 6_371_008.8;
	private static final double EPSILON_METERS = 0.001;

	public List<BusRouteSegmentPolylineData> match(
			String routeId,
			BusRoutePolylineData polyline,
			List<BusRouteStopMatchData> stops,
			OffsetDateTime sourceFetchedAt
	) {
		if (polyline == null || polyline.size() < 2 || stops == null || stops.size() < 2) {
			return List.of();
		}

		LineModel line = LineModel.of(polyline.points());
		double[] expectedPositions = expectedPositions(stops, line.totalLengthMeters());
		List<Projection> projections = new ArrayList<>(stops.size());
		double minimumPosition = 0.0;

		for (int index = 0; index < stops.size(); index++) {
			BusRouteStopMatchData stop = stops.get(index);
			Projection projection = projectInOrder(
					line, stop, minimumPosition, expectedPositions[index], SEARCH_WINDOW_METERS);
			projections.add(projection);
			if (projection != null) {
				minimumPosition = projection.positionMeters();
			}
		}

		List<BusRouteSegmentPolylineData> result = new ArrayList<>(stops.size() - 1);
		for (int index = 1; index < stops.size(); index++) {
			BusRouteStopMatchData from = stops.get(index - 1);
			BusRouteStopMatchData to = stops.get(index);
			Projection start = projections.get(index - 1);
			Projection end = projections.get(index);
			result.add(toSegment(routeId, line, from, to, start, end, sourceFetchedAt));
		}
		return List.copyOf(result);
	}

	private BusRouteSegmentPolylineData toSegment(
			String routeId,
			LineModel line,
			BusRouteStopMatchData from,
			BusRouteStopMatchData to,
			Projection start,
			Projection end,
			OffsetDateTime sourceFetchedAt
	) {
		Double expectedDistance = validDistance(to.sectionDistanceMeters());
		if (start == null || end == null || end.positionMeters() - start.positionMeters() <= EPSILON_METERS) {
			return failed(routeId, from, to, expectedDistance, sourceFetchedAt, "PROJECTION_FAILED");
		}

		List<BusRoutePolylineData.Point> points = line.slice(start, end);
		if (points.size() < 2) {
			return failed(routeId, from, to, expectedDistance, sourceFetchedAt, "SLICE_EMPTY");
		}

		double actualDistance = end.positionMeters() - start.positionMeters();
		Double distanceError = expectedDistance == null ? null : Math.abs(actualDistance - expectedDistance);
		List<String> reasons = new ArrayList<>();
		if (start.errorMeters() > STRICT_STOP_ERROR_METERS || end.errorMeters() > STRICT_STOP_ERROR_METERS) {
			reasons.add("STOP_ERROR_GT_20M");
		}
		if (distanceError == null) {
			reasons.add("SECTION_DISTANCE_MISSING");
		} else if (distanceError > STRICT_DISTANCE_ERROR_METERS) {
			reasons.add("DISTANCE_ERROR_GT_100M");
		}
		if (start.windowFallback() || end.windowFallback()) {
			reasons.add("EXPECTED_WINDOW_FALLBACK");
		}

		var quality = reasons.isEmpty() ? OK : REVIEW;
		return new BusRouteSegmentPolylineData(
				routeId, from.seq(), to.seq(), from.stopId(), to.stopId(), to.sectionId(), points,
				start.positionMeters() / line.totalLengthMeters(),
				end.positionMeters() / line.totalLengthMeters(),
				start.errorMeters(), end.errorMeters(), expectedDistance, actualDistance, distanceError,
				quality, reasons.isEmpty() ? null : String.join(",", reasons), MATCHER_VERSION, sourceFetchedAt);
	}

	private BusRouteSegmentPolylineData failed(
			String routeId,
			BusRouteStopMatchData from,
			BusRouteStopMatchData to,
			Double expectedDistance,
			OffsetDateTime sourceFetchedAt,
			String reason
	) {
		return new BusRouteSegmentPolylineData(
				routeId, from.seq(), to.seq(), from.stopId(), to.stopId(), to.sectionId(), List.of(),
				null, null, null, null, expectedDistance, null, null,
				FAILED, reason, MATCHER_VERSION, sourceFetchedAt);
	}

	private Projection projectInOrder(
			LineModel line,
			BusRouteStopMatchData stop,
			double minimumPosition,
			double expectedPosition,
			double windowMeters
	) {
		if (!validCoordinate(stop.longitude(), stop.latitude())) {
			return null;
		}
		double lower = Math.max(minimumPosition, expectedPosition - windowMeters);
		double upper = Math.min(line.totalLengthMeters(), expectedPosition + windowMeters);
		Projection projection = line.nearest(
				stop.longitude(), stop.latitude(), lower, upper, expectedPosition, false);
		if (projection == null || projection.errorMeters() > FALLBACK_PROJECTION_TRIGGER_METERS) {
			Projection fallback = line.nearest(
					stop.longitude(), stop.latitude(), minimumPosition, line.totalLengthMeters(),
					expectedPosition, true);
			if (fallback != null && (projection == null || fallback.errorMeters() < projection.errorMeters())) {
				return fallback;
			}
		}
		return projection;
	}

	private double[] expectedPositions(List<BusRouteStopMatchData> stops, double lineLengthMeters) {
		double[] cumulative = new double[stops.size()];
		for (int index = 1; index < stops.size(); index++) {
			Double distance = validDistance(stops.get(index).sectionDistanceMeters());
			cumulative[index] = cumulative[index - 1] + (distance == null ? 0.0 : distance);
		}
		double total = cumulative[cumulative.length - 1];
		if (total <= 0.0) {
			for (int index = 0; index < cumulative.length; index++) {
				cumulative[index] = lineLengthMeters * index / (cumulative.length - 1.0);
			}
			return cumulative;
		}
		for (int index = 0; index < cumulative.length; index++) {
			cumulative[index] = lineLengthMeters * cumulative[index] / total;
		}
		return cumulative;
	}

	private Double validDistance(Double value) {
		return value != null && Double.isFinite(value) && value >= 0.0 ? value : null;
	}

	private boolean validCoordinate(Double longitude, Double latitude) {
		return longitude != null && latitude != null
				&& Double.isFinite(longitude) && Double.isFinite(latitude)
				&& longitude >= 124.0 && longitude <= 132.0
				&& latitude >= 33.0 && latitude <= 39.0;
	}

	private record Projection(
			double positionMeters,
			double errorMeters,
			BusRoutePolylineData.Point point,
			int segmentIndex,
			boolean windowFallback
	) {
	}

	private record LineModel(
			List<BusRoutePolylineData.Point> points,
			double[] cumulativeMeters,
			double totalLengthMeters
	) {
		static LineModel of(List<BusRoutePolylineData.Point> points) {
			double[] cumulative = new double[points.size()];
			for (int index = 1; index < points.size(); index++) {
				cumulative[index] = cumulative[index - 1] + distance(points.get(index - 1), points.get(index));
			}
			if (cumulative[cumulative.length - 1] <= 0.0) {
				throw new IllegalArgumentException("Bus route polyline has zero length");
			}
			return new LineModel(points, cumulative, cumulative[cumulative.length - 1]);
		}

		Projection nearest(
				double longitude,
				double latitude,
				double lower,
				double upper,
				double expectedPosition,
				boolean fallback
		) {
			Projection best = null;
			double bestScore = Double.POSITIVE_INFINITY;
			for (int index = 0; index < points.size() - 1; index++) {
				double segmentStart = cumulativeMeters[index];
				double segmentEnd = cumulativeMeters[index + 1];
				if (segmentEnd < lower || segmentStart > upper || segmentEnd <= segmentStart) {
					continue;
				}
				double minT = Math.max(0.0, (lower - segmentStart) / (segmentEnd - segmentStart));
				double maxT = Math.min(1.0, (upper - segmentStart) / (segmentEnd - segmentStart));
				Projection candidate = project(points.get(index), points.get(index + 1), longitude, latitude,
						index, segmentStart, segmentEnd - segmentStart, minT, maxT, fallback);
				double score = candidate.errorMeters()
						+ Math.abs(candidate.positionMeters() - expectedPosition) * EXPECTED_POSITION_WEIGHT;
				if (best == null || score < bestScore) {
					best = candidate;
					bestScore = score;
				}
			}
			return best;
		}

		List<BusRoutePolylineData.Point> slice(Projection start, Projection end) {
			List<BusRoutePolylineData.Point> sliced = new ArrayList<>();
			appendDistinct(sliced, start.point());
			for (int index = start.segmentIndex() + 1; index <= end.segmentIndex(); index++) {
				double position = cumulativeMeters[index];
				if (position > start.positionMeters() + EPSILON_METERS
						&& position < end.positionMeters() - EPSILON_METERS) {
					appendDistinct(sliced, points.get(index));
				}
			}
			appendDistinct(sliced, end.point());
			return List.copyOf(sliced);
		}

		private static Projection project(
				BusRoutePolylineData.Point from,
				BusRoutePolylineData.Point to,
				double longitude,
				double latitude,
				int segmentIndex,
				double segmentStart,
				double segmentLength,
				double minT,
				double maxT,
				boolean fallback
		) {
			double referenceLatitude = Math.toRadians(latitude);
			double ax = Math.toRadians(from.x() - longitude) * Math.cos(referenceLatitude) * EARTH_RADIUS_METERS;
			double ay = Math.toRadians(from.y() - latitude) * EARTH_RADIUS_METERS;
			double bx = Math.toRadians(to.x() - longitude) * Math.cos(referenceLatitude) * EARTH_RADIUS_METERS;
			double by = Math.toRadians(to.y() - latitude) * EARTH_RADIUS_METERS;
			double dx = bx - ax;
			double dy = by - ay;
			double denominator = dx * dx + dy * dy;
			double t = denominator == 0.0 ? minT : -(ax * dx + ay * dy) / denominator;
			t = Math.max(minT, Math.min(maxT, t));
			double px = ax + t * dx;
			double py = ay + t * dy;
			BusRoutePolylineData.Point projected = new BusRoutePolylineData.Point(
					from.x() + t * (to.x() - from.x()),
					from.y() + t * (to.y() - from.y()));
			return new Projection(segmentStart + t * segmentLength, Math.hypot(px, py), projected,
					segmentIndex, fallback);
		}

		private static double distance(BusRoutePolylineData.Point left, BusRoutePolylineData.Point right) {
			double lat1 = Math.toRadians(left.y());
			double lat2 = Math.toRadians(right.y());
			double deltaLat = lat2 - lat1;
			double deltaLng = Math.toRadians(right.x() - left.x());
			double sinLat = Math.sin(deltaLat / 2.0);
			double sinLng = Math.sin(deltaLng / 2.0);
			double a = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLng * sinLng;
			return 2.0 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(a)));
		}

		private static void appendDistinct(
				List<BusRoutePolylineData.Point> target,
				BusRoutePolylineData.Point point
		) {
			if (target.isEmpty()) {
				target.add(point);
				return;
			}
			BusRoutePolylineData.Point last = target.get(target.size() - 1);
			if (Double.compare(last.x(), point.x()) != 0 || Double.compare(last.y(), point.y()) != 0) {
				target.add(point);
			}
		}
	}
}
