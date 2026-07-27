package watoo.grd.nextroute.application.bus.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.dto.BusRouteInfo;
import watoo.grd.nextroute.application.bus.dto.BusRouteStopInfo;
import watoo.grd.nextroute.application.bus.port.in.LoadBusRoutePolylineUseCase;
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
	private final LoadBusRoutePolylineUseCase loadBusRoutePolyline;

	@Override
	public void execute() {
		List<String> allRouteIds = busApiPort.getBusRouteIds();
		if (allRouteIds.isEmpty()) {
			log.warn("[StaticData] No route IDs fetched from Seoul Open API.");
			return;
		}

		// 노선 메타데이터도 개편·배차 변경이 있으므로 신규 노선만이 아니라 전체를 갱신한다.
		List<BusRoute> routes = loadBusRoutes(allRouteIds);
		busDataService.upsertRoutes(routes);

		Set<String> knownRouteIds = busDataService.findAllRoutes().stream()
				.map(BusRoute::getRouteId)
				.collect(java.util.stream.Collectors.toSet());
		List<String> syncTargets = allRouteIds.stream()
				.filter(knownRouteIds::contains)
				.toList();
		loadBusStopsAndRouteStops(syncTargets);
		loadBusRoutePolyline.execute();

		log.info("[StaticData] Reload completed: providerRoutes={}, refreshedRoutes={}, stopTargets={}",
				allRouteIds.size(), routes.size(), syncTargets.size());
	}

	private List<BusRoute> loadBusRoutes(List<String> routeIds) {
		List<BusRouteInfo> routeInfos = new ArrayList<>();
		int processed = 0;

		for (String routeId : routeIds) {
			try {
				List<BusRouteInfo> items = busApiPort.getRouteInfo(routeId);
				log.debug("[StaticData] Route metadata fetched: routeId={}, items={}", routeId, items.size());
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

		return new ArrayList<>(routeInfos.stream()
				.map(this::toRouteEntity)
				.collect(java.util.stream.Collectors.toMap(
						BusRoute::getRouteId,
						java.util.function.Function.identity(),
						(left, right) -> right,
						LinkedHashMap::new))
				.values());
	}

	private void loadBusStopsAndRouteStops(List<String> routeIds) {
		Map<String, BusStop> uniqueStops = new LinkedHashMap<>();
		Map<String, List<BusRouteStop>> routeStopsByRouteId = new LinkedHashMap<>();
		int processed = 0;

		for (String routeId : routeIds) {
			try {
        /// 노선별 경유 정류소 조회
				List<BusRouteStopInfo> items = busApiPort.getStationByRoute(routeId);

				if (items.isEmpty()) {
					log.warn("[StaticData] Empty route-stop response; preserving previous snapshot: routeId={}", routeId);
				} else {
					List<BusRouteStop> routeStops = items.stream()
							.sorted(Comparator.comparingInt(BusRouteStopInfo::seq))
							.collect(java.util.stream.Collectors.toMap(
									BusRouteStopInfo::seq,
									java.util.function.Function.identity(),
									(left, right) -> right,
									LinkedHashMap::new))
							.values().stream()
							.map(item -> {
								uniqueStops.put(item.stopId(), toStopEntity(item));
								return toRouteStopEntity(item);
							})
							.toList();
					routeStopsByRouteId.put(routeId, routeStops);
				}

				processed++;
				if (processed % 100 == 0) {
					log.info("[StaticData] Bus route stops progress: {}/{}", processed, routeIds.size());
				}
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				log.error("[StaticData] Failed to load stops for route {}: {}", routeId, e.getMessage());
			}
		}

		List<BusStop> syncedStops = busDataService.upsertStops(new ArrayList<>(uniqueStops.values()));

		int synchronizedRouteStops = 0;
		for (Map.Entry<String, List<BusRouteStop>> entry : routeStopsByRouteId.entrySet()) {
			busDataService.replaceRouteStops(entry.getKey(), entry.getValue());
			synchronizedRouteStops += entry.getValue().size();
		}

		log.info("[StaticData] Stops: {} synchronized, RouteStops: {} synchronized across {} routes",
				syncedStops.size(), synchronizedRouteStops, routeStopsByRouteId.size());
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
