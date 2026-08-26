package watoo.grd.nextroute.application.bus.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.domain.bus.service.BusRoutePolylineSnapshot;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineData;
import watoo.grd.nextroute.application.bus.port.in.BuildBusRouteSegmentPolylinesUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusRouteSegmentPolylinePort;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.bus.service.BusRoutePolylineDataService;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusRouteSegmentPolylineBuildService implements BuildBusRouteSegmentPolylinesUseCase {

	private final BusRoutePolylineDataService polylineDataService;
	private final BusRouteStopRepository busRouteStopRepository;
	private final OrderedBusRoutePolylineMatcher matcher;
	private final BusRouteSegmentPolylinePort segmentPort;

	@Override
	public void buildRoute(String routeId) {
		BusRoutePolylineSnapshot snapshot = polylineDataService.findSnapshotByRouteId(routeId)
				.orElseThrow(() -> new IllegalArgumentException("Bus route polyline not found: " + routeId));
		List<BusRouteSegmentPolylineData> segments = matcher.match(
				routeId,
				snapshot.data(),
				busRouteStopRepository.findMatchDataByRouteId(routeId),
				snapshot.fetchedAt());
		segmentPort.replaceRouteSegments(routeId, segments);

		long ok = segments.stream()
				.filter(segment -> segment.qualityStatus() == BusRouteSegmentPolylineData.QualityStatus.OK)
				.count();
		long review = segments.stream()
				.filter(segment -> segment.qualityStatus() == BusRouteSegmentPolylineData.QualityStatus.REVIEW)
				.count();
		long failed = segments.size() - ok - review;
		log.info("[BusRouteSegmentPolyline] Built routeId={}, total={}, ok={}, review={}, failed={}",
				routeId, segments.size(), ok, review, failed);
	}
}
