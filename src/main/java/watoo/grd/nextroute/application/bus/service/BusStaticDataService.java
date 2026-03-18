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

	private final BusApiPort busApiPort;
	private final BusDataService busDataService;

	@Override
	public void execute() {
    //이걸로 버스 노선 번호를 받음
		List<String> allRouteIds = busApiPort.getBusRouteIds();
		if (allRouteIds.isEmpty()) {
			log.warn("[StaticData] No route IDs fetched from Seoul Open API.");
			return;
		}
    //여기서 중복 걸러주고
		List<String> newRouteIds = allRouteIds.stream()
				.filter(id -> !busDataService.existsRouteByRouteId(id))
				.toList();

		log.info("[StaticData] Total {} routes from API, {} new to fetch",
				allRouteIds.size(), newRouteIds.size());

		if (newRouteIds.isEmpty()) {
			log.info("[StaticData] All routes already loaded. Skipping.");
			return;
		}
    //노선 정보 가져와주고
		List<BusRoute> routes = loadBusRoutes(newRouteIds);
		if (!routes.isEmpty()) {
			busDataService.saveAllRoutes(routes);
			log.info("[StaticData] Saved {} new bus routes", routes.size());
		}
    //
		loadBusStopsAndRouteStops(routes);
	}

	private List<BusRoute> loadBusRoutes(List<String> routeIds) {
		List<BusRouteInfo> routeInfos = new ArrayList<>();
		int processed = 0;

		for (String routeId : routeIds) {
			try {
				List<BusRouteInfo> items = busApiPort.getRouteInfo(routeId);
				routeInfos.addAll(items);

				processed++;
				if (processed % 100 == 0) {
					log.info("[StaticData] Bus route info progress: {}/{}", processed, routeIds.size());
				}
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return List.of();
			} catch (Exception e) {
				log.error("[StaticData] Failed to get route info for '{}': {}", routeId, e.getMessage());
			}
		}

		return routeInfos.stream()
				.map(this::toRouteEntity)
				.toList();
	}

	private void loadBusStopsAndRouteStops(List<BusRoute> routes) {
		Map<String, BusStop> uniqueStops = new LinkedHashMap<>();
		List<BusRouteStop> allRouteStops = new ArrayList<>();
		int processed = 0;

		for (BusRoute route : routes) {
			try {
        /// 노선별 경유 정류소 조회
				List<BusRouteStopInfo> items = busApiPort.getStationByRoute(route.getRouteId());

				for (BusRouteStopInfo item : items) {
					uniqueStops.putIfAbsent(item.stopId(), toStopEntity(item));
					allRouteStops.add(toRouteStopEntity(item));
				}

				processed++;
				if (processed % 100 == 0) {
					log.info("[StaticData] Bus route stops progress: {}/{}", processed, routes.size());
				}
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				log.error("[StaticData] Failed to load stops for route {}: {}",
						route.getRouteId(), e.getMessage());
			}
		}

		List<BusStop> newStops = uniqueStops.values().stream()
				.filter(s -> !busDataService.existsStopByStopId(s.getStopId()))
				.toList();

		if (!newStops.isEmpty()) {
			busDataService.saveAllStops(new ArrayList<>(newStops));
		}

		List<BusRouteStop> newRouteStops = allRouteStops.stream()
				.filter(rs -> !busDataService.existsRouteStop(rs.getRouteId(), rs.getStopId(), rs.getSeq()))
				.toList();

		if (!newRouteStops.isEmpty()) {
			busDataService.saveAllRouteStops(newRouteStops);
		}

		log.info("[StaticData] Stops: {} new / {} skipped, RouteStops: {} new / {} skipped",
				newStops.size(), uniqueStops.size() - newStops.size(),
				newRouteStops.size(), allRouteStops.size() - newRouteStops.size());
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
				.lastBusYn(info.lastBusYn())
				.firstLowTime(info.firstLowTime())
				.lastLowTime(info.lastLowTime())
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
				.sectionDistance(info.sectionDistance())
				.direction(info.direction())
				.transferYn(info.transferYn())
				.stationNo(info.stationNo())
				.beginTm(info.beginTm())
				.lastTm(info.lastTm())
				.turnStopId(info.turnStopId())
				.sectionSpeed(info.sectionSpeed())
				.build();
	}
}
