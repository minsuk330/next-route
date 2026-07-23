package watoo.grd.nextroute.application.bus.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.dto.BusRoutePathPoint;
import watoo.grd.nextroute.application.bus.dto.BusRoutePolylineBackfillResult;
import watoo.grd.nextroute.application.bus.dto.BusRoutePolylineData;
import watoo.grd.nextroute.application.bus.exception.BusApiLimitExceededException;
import watoo.grd.nextroute.application.bus.port.in.LoadBusRoutePolylineUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.domain.bus.service.BusRoutePolylineDataService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
public class BusRoutePolylineBackfillService implements LoadBusRoutePolylineUseCase {

	private static final double MIN_LONGITUDE = 124.0;
	private static final double MAX_LONGITUDE = 132.0;
	private static final double MIN_LATITUDE = 33.0;
	private static final double MAX_LATITUDE = 39.0;

	private final BusApiPort busApiPort;
	private final BusRoutePolylineDataService dataService;
	private final long requestIntervalMillis;

	public BusRoutePolylineBackfillService(
			BusApiPort busApiPort,
			BusRoutePolylineDataService dataService,
			@Value("${batch.bus-route-polyline.request-interval-ms:200}") long requestIntervalMillis
	) {
		this.busApiPort = busApiPort;
		this.dataService = dataService;
		this.requestIntervalMillis = Math.max(0, requestIntervalMillis);
	}

	@Override
	public BusRoutePolylineBackfillResult execute() {
		List<String> routeIds = dataService.findRouteIdsWithoutPolyline();
		int success = 0;
		int skipped = 0;
		int empty = 0;
		int invalid = 0;
		int failed = 0;
		boolean aborted = false;

		log.info("[BusRoutePolyline] Backfill started: targets={}", routeIds.size());

		for (int index = 0; index < routeIds.size(); index++) {
			String routeId = routeIds.get(index);
			try {
				// 대상 조회 후 다른 실행이 먼저 저장한 경우의 중복 API 호출도 피한다.
				if (dataService.existsByRouteId(routeId)) {
					skipped++;
				} else {
					List<BusRoutePathPoint> rawPoints = busApiPort.getRoutePath(routeId);
					if (rawPoints.isEmpty()) {
						empty++;
						log.warn("[BusRoutePolyline] Empty route path: routeId={}", routeId);
					} else {
						BusRoutePolylineData data = normalize(rawPoints);
						if (data.size() < 2) {
							invalid++;
							log.warn("[BusRoutePolyline] Invalid route path: routeId={}, rawPoints={}, validPoints={}",
									routeId, rawPoints.size(), data.size());
						} else if (dataService.saveIfAbsent(routeId, data)) {
							success++;
						} else {
							skipped++;
						}
					}
				}
			} catch (BusApiLimitExceededException e) {
				aborted = true;
				log.error("[BusRoutePolyline] Provider daily limit reached; aborting at routeId={}: {}",
						routeId, e.getMessage());
				break;
			} catch (Exception e) {
				failed++;
				log.error("[BusRoutePolyline] Failed routeId={}: {}", routeId, e.getMessage());
			}

			if (index + 1 < routeIds.size() && !sleepBeforeNextRequest()) {
				aborted = true;
				break;
			}

			if ((index + 1) % 100 == 0) {
				log.info("[BusRoutePolyline] Progress: {}/{}", index + 1, routeIds.size());
			}
		}

		BusRoutePolylineBackfillResult result = new BusRoutePolylineBackfillResult(
				routeIds.size(), success, skipped, empty, invalid, failed, aborted);
		log.info("[BusRoutePolyline] Backfill finished: {}", result);
		return result;
	}

	BusRoutePolylineData normalize(List<BusRoutePathPoint> rawPoints) {
		List<BusRoutePolylineData.Point> normalized = new ArrayList<>();

		rawPoints.stream()
				.filter(this::isValid)
				.sorted(Comparator.comparingInt(BusRoutePathPoint::sequence))
				.map(point -> new BusRoutePolylineData.Point(point.longitude(), point.latitude()))
				.forEach(point -> {
					if (normalized.isEmpty() || !samePoint(normalized.get(normalized.size() - 1), point)) {
						normalized.add(point);
					}
				});

		return new BusRoutePolylineData(normalized);
	}

	private boolean isValid(BusRoutePathPoint point) {
		if (point == null || point.sequence() == null || point.sequence() <= 0
				|| point.longitude() == null || point.latitude() == null) {
			return false;
		}
		double longitude = point.longitude();
		double latitude = point.latitude();
		return Double.isFinite(longitude) && Double.isFinite(latitude)
				&& longitude >= MIN_LONGITUDE && longitude <= MAX_LONGITUDE
				&& latitude >= MIN_LATITUDE && latitude <= MAX_LATITUDE;
	}

	private boolean samePoint(BusRoutePolylineData.Point left, BusRoutePolylineData.Point right) {
		return Double.compare(left.x(), right.x()) == 0
				&& Double.compare(left.y(), right.y()) == 0;
	}

	private boolean sleepBeforeNextRequest() {
		if (requestIntervalMillis == 0) {
			return true;
		}
		try {
			Thread.sleep(requestIntervalMillis);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("[BusRoutePolyline] Backfill interrupted");
			return false;
		}
	}
}
