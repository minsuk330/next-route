package watoo.grd.nextroute.domain.bus.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.bus.entity.*;
import watoo.grd.nextroute.domain.bus.repository.*;
import watoo.grd.nextroute.domain.bus.repository.NearbyBusStopProjection;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BusDataService {

  private final BusRouteRepository busRouteRepository;
  private final BusStopRepository busStopRepository;
  private final BusRouteStopRepository busRouteStopRepository;
  private final BusArrivalRawRepository busArrivalRawRepository;
  private final BusArrivalCandidateRawRepository busArrivalCandidateRawRepository;
  private final BusPositionRawRepository busPositionRawRepository;

	@Transactional
	public BusRoute saveRoute(BusRoute route) {
		return busRouteRepository.save(route);
	}

	@Transactional
	public List<BusRoute> saveAllRoutes(List<BusRoute> routes) {
		return busRouteRepository.saveAll(routes);
	}

	@Transactional
	public BusStop saveStop(BusStop stop) {
		return busStopRepository.save(stop);
	}

	@Transactional
	public List<BusStop> saveAllStops(List<BusStop> stops) {
		List<BusStop> saved = busStopRepository.saveAll(stops);
		busStopRepository.backfillGeom();
		return saved;
	}

	@Transactional
	public List<BusRouteStop> saveAllRouteStops(List<BusRouteStop> routeStops) {
		return busRouteStopRepository.saveAll(routeStops);
	}

	@Transactional
	public List<BusArrivalRaw> saveAllArrivals(List<BusArrivalRaw> arrivals) {
		return busArrivalRawRepository.saveAll(arrivals);
	}

	@Transactional
	public List<BusArrivalCandidateRaw> saveAllArrivalCandidates(List<BusArrivalCandidateRaw> candidates) {
		// lifecycle_id 기준 idempotent insert.
		// DB 커밋 후 Redis 삭제 전 크래시로 같은 snapshot이 재finalize돼도 중복 행을 만들지 않는다.
		// (1차 방어: 이미 저장된 lifecycle_id 제외 / 2차 방어: lifecycle_id unique index)
		List<String> lifecycleIds = candidates.stream()
				.map(BusArrivalCandidateRaw::getLifecycleId)
				.filter(Objects::nonNull)
				.toList();

		Set<String> existing = lifecycleIds.isEmpty()
				? Set.of()
				: new HashSet<>(busArrivalCandidateRawRepository.findExistingLifecycleIds(lifecycleIds));

		List<BusArrivalCandidateRaw> toSave = candidates.stream()
				.filter(candidate -> candidate.getLifecycleId() == null || !existing.contains(candidate.getLifecycleId()))
				.toList();

		if (toSave.isEmpty()) {
			return List.of();
		}
		return busArrivalCandidateRawRepository.saveAll(toSave);
	}

	@Transactional
	public List<BusPositionRaw> saveAllPositions(List<BusPositionRaw> positions) {
		return busPositionRawRepository.saveAll(positions);
	}

	public boolean existsRouteByRouteId(String routeId) {
		return busRouteRepository.existsByRouteId(routeId);
	}

	public boolean existsStopByStopId(String stopId) {
		return busStopRepository.existsByStopId(stopId);
	}

	public boolean existsRouteStop(String routeId, String stopId, Integer seq) {
		return busRouteStopRepository.existsByRouteIdAndStopIdAndSeq(routeId, stopId, seq);
	}

	public List<BusRoute> findAllRoutes() {
		return busRouteRepository.findAll();
	}

	public List<BusRouteStop> findRouteStops(String routeId) {
		return busRouteStopRepository.findByRouteIdOrderBySeq(routeId);
	}

	public List<BusRoute> findRoutesByNames(List<String> routeNames) {
		return busRouteRepository.findByRouteNameIn(routeNames);
	}

	public List<BusArrivalRaw> findLatestArrivalsByStopId(String stopId, LocalDateTime from) {
		return busArrivalRawRepository.findLatestByStopId(stopId, from);
	}

	public List<NearbyBusStopProjection> findNearbyStops(double lat, double lng, double radiusMeters, int limit) {
		return busStopRepository.findNearby(lat, lng, radiusMeters, limit);
	}
}
