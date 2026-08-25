package watoo.grd.nextroute.domain.bus.service;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.entity.BusStop;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;

@Service
@RequiredArgsConstructor
public class BusCatalogWriter {

	private final BusRouteRepository busRouteRepository;
	private final BusStopRepository busStopRepository;
	private final BusRouteStopRepository busRouteStopRepository;

	@Transactional
	public List<BusRoute> upsertRoutes(List<BusRoute> routes) {
		if (routes.isEmpty()) {
			return List.of();
		}
		Map<String, BusRoute> existingByRouteId = busRouteRepository
				.findByRouteIdIn(routes.stream().map(BusRoute::getRouteId).toList())
				.stream()
				.collect(Collectors.toMap(BusRoute::getRouteId, Function.identity()));

		List<BusRoute> merged = routes.stream()
				.map(route -> {
					BusRoute existing = existingByRouteId.get(route.getRouteId());
					if (existing == null) {
						return route;
					}
					existing.refresh(route);
					return existing;
				})
				.toList();
		return busRouteRepository.saveAll(merged);
	}

	@Transactional
	public List<BusStop> upsertStops(List<BusStop> stops) {
		if (stops.isEmpty()) {
			return List.of();
		}
		Map<String, BusStop> existingByStopId = busStopRepository
				.findByStopIdIn(stops.stream().map(BusStop::getStopId).toList())
				.stream()
				.collect(Collectors.toMap(BusStop::getStopId, Function.identity()));
		List<BusStop> merged = stops.stream()
				.map(stop -> {
					BusStop existing = existingByStopId.get(stop.getStopId());
					if (existing == null) {
						return stop;
					}
					existing.refresh(stop.getStopName(), stop.getArsId(), stop.getLatitude(), stop.getLongitude());
					return existing;
				})
				.toList();
		List<BusStop> saved = busStopRepository.saveAll(merged);
		busStopRepository.refreshGeom();
		return saved;
	}

	/** API 응답이 성공한 노선의 경유 정류장 스냅샷을 seq 기준으로 교체한다. */
	@Transactional
	public List<BusRouteStop> replaceRouteStops(String routeId, List<BusRouteStop> routeStops) {
		List<BusRouteStop> existing = busRouteStopRepository.findByRouteIdOrderBySeq(routeId);
		Map<Integer, BusRouteStop> existingBySeq = existing.stream()
				.collect(Collectors.toMap(BusRouteStop::getSeq, Function.identity(), (left, right) -> left,
						LinkedHashMap::new));

		List<BusRouteStop> merged = routeStops.stream()
				.map(source -> {
					BusRouteStop current = existingBySeq.remove(source.getSeq());
					if (current == null) {
						return source;
					}
					current.refresh(source);
					return current;
				})
				.toList();

		if (!existingBySeq.isEmpty()) {
			busRouteStopRepository.deleteAllInBatch(existingBySeq.values());
		}
		return busRouteStopRepository.saveAll(merged);
	}
}
