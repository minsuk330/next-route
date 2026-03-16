package watoo.grd.nextroute.application.bus.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.port.in.CollectBusPositionUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.domain.bus.entity.BusPositionRaw;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusPositionService implements CollectBusPositionUseCase {

	private final BusApiPort busApiPort;
	private final BusDataService busDataService;

	@Override
	public void execute() {
		List<String> routeIds = busDataService.findAllRoutes().stream()
				.map(BusRoute::getRouteId)
				.toList();

		if (routeIds.isEmpty()) {
			log.warn("[BusPosition] No routes to collect. Run StaticDataLoader first.");
			return;
		}

		LocalDateTime collectedAt = LocalDateTime.now();
		int totalSaved = 0;

		log.info("[BusPosition] Starting collection for {} routes", routeIds.size());

		for (String routeId : routeIds) {
			try {
				List<BusPositionInfo> items = busApiPort.getBusPosByRtid(routeId);
				List<BusPositionRaw> entities = items.stream()
						.map(info -> toEntity(info, routeId, collectedAt))
						.toList();
				busDataService.saveAllPositions(entities);
				totalSaved += entities.size();
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				log.error("[BusPosition] Failed for route {}: {}", routeId, e.getMessage());
			}
		}

		log.info("[BusPosition] Completed. Saved {} records", totalSaved);
	}

	private BusPositionRaw toEntity(BusPositionInfo info, String routeId, LocalDateTime collectedAt) {
		return BusPositionRaw.builder()
				.collectedAt(collectedAt)
				.routeId(routeId)
				.vehicleId(info.vehicleId())
				.latitude(info.latitude())
				.longitude(info.longitude())
				.stopSeq(info.stopSeq())
				.sectionSpeed(info.sectionSpeed())
				.sectionOrder(info.sectionOrder())
				.build();
	}
}
