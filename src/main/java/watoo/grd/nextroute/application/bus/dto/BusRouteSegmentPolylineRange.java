package watoo.grd.nextroute.application.bus.dto;

import java.util.List;

public record BusRouteSegmentPolylineRange(
		String routeId,
		int fromSeq,
		int toSeq,
		boolean hasGap,
		List<BusRoutePolylineData.Point> points,
		List<SegmentSummary> segments
) {
	public record SegmentSummary(
			int fromSeq,
			int toSeq,
			String sectionId,
			BusRouteSegmentPolylineData.QualityStatus qualityStatus,
			String qualityReason
	) {
	}
}
