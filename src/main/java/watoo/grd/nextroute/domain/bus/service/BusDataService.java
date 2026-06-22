package watoo.grd.nextroute.domain.bus.service;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.bus.dto.BusArrivalCandidateLabelRow;
import watoo.grd.nextroute.application.bus.dto.BusPositionLabelRow;
import watoo.grd.nextroute.domain.bus.entity.*;
import watoo.grd.nextroute.domain.bus.repository.*;
import watoo.grd.nextroute.domain.bus.repository.NearbyBusStopProjection;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
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
  private final BusArrivalLabelEventRepository busArrivalLabelEventRepository;
  private final EntityManager entityManager;

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

	// ===== 정류장 선택 UI 조회 =====

	/** 주어진 정류장들 중 지원 노선을 경유하는 정류장 id (배지 판정용). */
	public Set<String> findSupportedStopIds(Collection<String> stopIds, Collection<String> routeIds) {
		if (stopIds.isEmpty() || routeIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(busRouteStopRepository.findSupportedStopIds(stopIds, routeIds));
	}

	/** 정류장 경유 노선 목록 (정류장→노선 역조회). */
	public List<StopRouteProjection> findRoutesByStopId(String stopId) {
		return busRouteStopRepository.findRoutesByStopId(stopId);
	}

	/** 노선 경유 정류장 목록 (seq 순, 좌표 포함). */
	public List<RouteStopProjection> findStopsByRouteId(String routeId) {
		return busRouteStopRepository.findStopsByRouteId(routeId);
	}

	/** 버스번호 prefix 자동완성. */
	public List<BusRoute> searchRoutesByNamePrefix(String prefix) {
		return busRouteRepository.findTop20ByRouteNameStartingWithOrderByRouteName(prefix);
	}

	/** 정류장명 prefix 자동완성. */
	public List<BusStop> searchStopsByNamePrefix(String prefix) {
		return busStopRepository.findTop20ByStopNameStartingWithOrderByStopName(prefix);
	}

  public Optional<BusStop> findStopById(String stopId) {
    return busStopRepository.findByStopId(stopId);
  }

	// ===== BusArrivalLabelEvent =====

	@Transactional
	public int deleteLabelEventsByServiceDate(LocalDate serviceDate) {
		return busArrivalLabelEventRepository.deleteByServiceDate(serviceDate);
	}

	@Transactional
	public List<BusArrivalLabelEvent> saveAllLabelEvents(List<BusArrivalLabelEvent> events) {
		return busArrivalLabelEventRepository.saveAll(events);
	}

	public long countLabelEventsByServiceDate(LocalDate serviceDate) {
		return busArrivalLabelEventRepository.countByServiceDate(serviceDate);
	}

	public List<String> findCandidateRouteIdsByFinalizedAtBetween(
			LocalDateTime from, LocalDateTime to) {
		return busArrivalCandidateRawRepository.findDistinctRouteIdsByFinalizedAtBetween(from, to);
	}

	public List<BusArrivalCandidateLabelRow> findCandidateLabelRowsByRoute(
			String routeId, LocalDateTime from, LocalDateTime to) {
		return busArrivalCandidateRawRepository.findLabelRowsByRouteIdAndFinalizedAtBetween(routeId, from, to);
	}

	public List<BusPositionLabelRow> findPositionLabelRowsByRoute(
			String routeId, LocalDateTime from, LocalDateTime to) {
		return busPositionRawRepository.findLabelRowsByRouteIdAndCollectedAtBetween(routeId, from, to);
	}

	/** Hibernate 1차 캐시 플러시+초기화. 청크 저장 후 heap bloat 방지. */
	@Transactional
	public void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

  public List<BusRouteStop> findBusRouteByStopAndRoute(String stopId, String routeId) {
    return busRouteStopRepository.findByRouteIdAndStopId(routeId, stopId);
  }
}
