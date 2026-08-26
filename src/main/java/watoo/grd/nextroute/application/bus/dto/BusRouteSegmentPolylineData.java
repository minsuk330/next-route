package watoo.grd.nextroute.application.bus.dto;

import java.time.OffsetDateTime;
import watoo.grd.nextroute.domain.bus.service.BusRoutePolylineData;
import java.util.List;

public record BusRouteSegmentPolylineData(
		String routeId,
		int fromSeq,
		int toSeq,
		String fromStopId,
		String toStopId,
		String sectionId,
		List<BusRoutePolylineData.Point> points,
		Double startFraction,
		Double endFraction,
		Double startErrorMeters,
		Double endErrorMeters,
		Double expectedDistanceMeters,
		Double actualDistanceMeters,
		Double distanceErrorMeters,
		QualityStatus qualityStatus,
		String qualityReason,
		String matcherVersion,
		OffsetDateTime sourceFetchedAt
) {
	public BusRouteSegmentPolylineData {
		points = points == null ? List.of() : List.copyOf(points);
	}

	public enum QualityStatus {
		OK,
		REVIEW,
		FAILED
	}
}
