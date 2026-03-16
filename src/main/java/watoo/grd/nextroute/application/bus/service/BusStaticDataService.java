package watoo.grd.nextroute.application.bus.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.dto.BusRouteInfo;
import watoo.grd.nextroute.application.bus.dto.BusRouteStopInfo;
import watoo.grd.nextroute.application.bus.port.in.LoadBusStaticDataUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.entity.BusStop;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusStaticDataService implements LoadBusStaticDataUseCase {

	private static final String[] ROUTE_SEARCH_KEYWORDS = {
			"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"
	};

	private final BusApiPort busApiPort;
	private final BusDataService busDataService;

	@Override
	public void execute() {
		List<BusRoute> routes = loadBusRoutes();
		if (routes.isEmpty()) {
			log.warn("[StaticData] No bus routes fetched from API.");
			return;
		}

		busDataService.saveAllRoutes(routes);
		log.info("[StaticData] Saved {} bus routes", routes.size());

		loadBusStopsAndRouteStops(routes);
	}

	private List<BusRoute> loadBusRoutes() {
		Map<String, BusRouteInfo> uniqueRoutes = new LinkedHashMap<>();

		for (String keyword : ROUTE_SEARCH_KEYWORDS) {
			try {
				List<BusRouteInfo> items = busApiPort.getBusRouteList(keyword);
				for (BusRouteInfo item : items) {
					uniqueRoutes.putIfAbsent(item.routeId(), item);
				}
				log.info("[StaticData] Bus search '{}': {} routes found", keyword, items.size());
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return List.of();
			} catch (Exception e) {
				log.error("[StaticData] Failed to search bus routes for '{}': {}", keyword, e.getMessage());
			}
		}

		return uniqueRoutes.values().stream()
				.map(this::toRouteEntity)
				.toList();
	}

	private void loadBusStopsAndRouteStops(List<BusRoute> routes) {
		Map<String, BusStop> uniqueStops = new LinkedHashMap<>();
		List<BusRouteStop> allRouteStops = new ArrayList<>();
		int processed = 0;

		for (BusRoute route : routes) {
			try {
				List<BusRouteStopInfo> items = busApiPort.getStationByRoute(route.getRouteId());

				for (BusRouteStopInfo item : items) {
					uniqueStops.putIfAbsent(item.stopId(), toStopEntity(item));
					allRouteStops.add(toRouteStopEntity(item));
				}

				processed++;
				if (processed % 100 == 0) {
					log.info("[StaticData] Bus route stops progress: {}/{}", processed, routes.size());
				}
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				log.error("[StaticData] Failed to load stops for route {}: {}",
						route.getRouteId(), e.getMessage());
			}
		}

		busDataService.saveAllStops(new ArrayList<>(uniqueStops.values()));
		busDataService.saveAllRouteStops(allRouteStops);
		log.info("[StaticData] Saved {} bus stops, {} route-stops",
				uniqueStops.size(), allRouteStops.size());
	}

	private BusRoute toRouteEntity(BusRouteInfo info) {
		return BusRoute.builder()
				.routeId(info.routeId())
				.routeName(info.routeName())
				.routeType(info.routeType())
				.startStation(info.startStation())
				.endStation(info.endStation())
				.term(info.term())
				.firstBusTime(info.firstBusTime())
				.lastBusTime(info.lastBusTime())
				.companyName(info.companyName())
				.totalDistance(info.totalDistance())
				.build();
	}

	private BusStop toStopEntity(BusRouteStopInfo info) {
		return BusStop.builder()
				.stopId(info.stopId())
				.stopName(info.stopName())
				.arsId(info.arsId())
				.latitude(info.latitude())
				.longitude(info.longitude())
				.build();
	}

	private BusRouteStop toRouteStopEntity(BusRouteStopInfo info) {
		return BusRouteStop.builder()
				.routeId(info.routeId())
				.stopId(info.stopId())
				.seq(info.seq())
				.sectionId(info.sectionId())
				.latitude(info.latitude())
				.longitude(info.longitude())
				.sectionDistance(info.sectionDistance())
				.direction(info.direction())
				.transferYn(info.transferYn())
				.build();
	}
}
