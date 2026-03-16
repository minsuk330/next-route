package watoo.grd.nextroute.application.bus.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.port.in.CollectBusArrivalUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalRaw;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusArrivalService implements CollectBusArrivalUseCase {

	private final BusApiPort busApiPort;
	private final BusDataService busDataService;

	@Override
	public void execute() {
		List<String> routeIds = busDataService.findAllRoutes().stream()
				.map(BusRoute::getRouteId)
				.toList();

		if (routeIds.isEmpty()) {
			log.warn("[BusArrival] No routes to collect. Run StaticDataLoader first.");
			return;
		}

		LocalDateTime collectedAt = LocalDateTime.now();
		int totalSaved = 0;

		log.info("[BusArrival] Starting collection for {} routes", routeIds.size());

		for (String routeId : routeIds) {
			try {
				List<BusArrivalInfo> items = busApiPort.getArrInfoByRouteAll(routeId);
				List<BusArrivalRaw> entities = items.stream()
						.map(info -> toEntity(info, collectedAt))
						.toList();
				busDataService.saveAllArrivals(entities);
				totalSaved += entities.size();
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				log.error("[BusArrival] Failed for route {}: {}", routeId, e.getMessage());
			}
		}

		log.info("[BusArrival] Completed. Saved {} records", totalSaved);
	}

	private BusArrivalRaw toEntity(BusArrivalInfo info, LocalDateTime collectedAt) {
		return BusArrivalRaw.builder()
				.collectedAt(collectedAt)
				.routeId(info.routeId())
				.stopId(info.stopId())
				.seq(info.seq())
				.predictTime1(info.predictTime1())
				.sectionTime1(info.sectionTime1())
				.sectionSpeed1(info.sectionSpeed1())
				.isArrive1(info.isArrive1())
				.vehicleId1(info.vehicleId1())
				.plateNo1(info.plateNo1())
				.predictTime2(info.predictTime2())
				.sectionTime2(info.sectionTime2())
				.sectionSpeed2(info.sectionSpeed2())
				.isArrive2(info.isArrive2())
				.vehicleId2(info.vehicleId2())
				.plateNo2(info.plateNo2())
				.build();
	}
}
