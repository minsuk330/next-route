package watoo.grd.nextroute.domain.bus.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.bus.entity.*;
import watoo.grd.nextroute.domain.bus.repository.*;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BusDataService {

  private final BusRouteRepository busRouteRepository;
  private final BusStopRepository busStopRepository;
  private final BusRouteStopRepository busRouteStopRepository;
  private final BusArrivalRawRepository busArrivalRawRepository;
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
		return busStopRepository.saveAll(stops);
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
}
