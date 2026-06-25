package watoo.grd.nextroute.application.bus.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.exception.BusApiBlockedException;
import watoo.grd.nextroute.application.bus.port.in.CollectBusPositionUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.domain.bus.entity.BusPositionRaw;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusPositionService implements CollectBusPositionUseCase {

	private static final long API_CALL_INTERVAL_MS = 100;

	private final BusApiPort busApiPort;
	private final BusDataService busDataService;
	private final TargetRouteProvider targetRouteProvider;
	@Qualifier("positionApiCallBudget")
	private final BusApiCallBudget budget;
	private final Clock clock;

	@Value("${collector.bus-position.daily-budget:50000}")
	private int dailyBudget = 50000;

	@Override
	public void execute() {
		List<String> targetNames = targetRouteProvider.activeRouteNames();

		if (targetNames.isEmpty()) {
			log.warn("[BusPosition] No target routes configured. Set collector.bus-arrival.target-route-names.");
			return;
		}

		List<BusRoute> matchedRoutes = busDataService.findRoutesByNames(targetNames);
		List<String> routeIds = matchedRoutes.stream()
				.map(BusRoute::getRouteId)
				.toList();

		if (routeIds.isEmpty()) {
			log.warn("[BusPosition] No matching routes found in DB for {}. Run StaticDataLoader first.", targetNames);
			return;
		}

		if (routeIds.size() < targetNames.size()) {
			log.warn("[BusPosition] Only {}/{} target routes found in DB.", routeIds.size(), targetNames.size());
		}

		LocalDateTime collectedAt = LocalDateTime.now(clock);
		int totalSaved = 0;
		int successfulRoutes = 0;
		int failedRoutes = 0;

//		log.info("[BusPosition] Starting collection for {} target routes (budget: {}/{})",
//				routeIds.size(), budget.getUsed(), dailyBudget);

		for (String routeId : routeIds) {
			if (!budget.canMakeCall(dailyBudget)) {
				log.warn("[BusPosition] Daily budget exhausted ({}/{}). Stopping before route {}.",
						budget.getUsed(), dailyBudget, routeId);
				break;
			}

			boolean callRecorded = false;
			try {
				List<BusPositionInfo> items = busApiPort.getBusPosByRtid(routeId);
				budget.recordCall();
				callRecorded = true;

				List<BusPositionRaw> entities = items.stream()
						.map(info -> toEntity(info, routeId, collectedAt))
						.toList();
				if (!entities.isEmpty()) {
					busDataService.saveAllPositions(entities);
					totalSaved += entities.size();
				}
				successfulRoutes++;
				Thread.sleep(API_CALL_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (BusApiBlockedException e) {
				log.warn("[BusPosition] API blocked. Stopping collection without budget record: {}",
						e.getMessage());
				return;
			} catch (Exception e) {
				if (!callRecorded) {
					budget.recordCall();
				}
				failedRoutes++;
				log.error("[BusPosition] Failed for route {}: {}", routeId, e.getMessage());
			}
		}

//		log.info("[BusPosition] Completed. Saved {} records across {} routes, failed {} routes (budget: {}/{})",
//				totalSaved, successfulRoutes, failedRoutes, budget.getUsed(), dailyBudget);
	}

	private BusPositionRaw toEntity(BusPositionInfo info, String routeId, LocalDateTime collectedAt) {
		return BusPositionRaw.builder()
				.collectedAt(collectedAt)
				.routeId(routeId)
				.vehicleId(info.vehicleId())
				.nextStopTime(info.nextStopTime())
				.sectionOrder(info.sectionOrder())
				.sectionDistance(info.sectionDistance())
				.routeDistance(info.routeDistance())
				.stopFlag(info.stopFlag())
				.sectionId(info.sectionId())
				.dataTm(info.dataTm())
				.plainNo(info.plainNo())
				.busType(info.busType())
				.lastStopTime(info.lastStopTime())
				.lastStopId(info.lastStopId())
				.posX(info.posX())
				.posY(info.posY())
				.isFullFlag(info.isFullFlag())
				.isLastYn(info.isLastYn())
				.fullSectionDistance(info.fullSectionDistance())
				.nextStopId(info.nextStopId())
				.congestion(info.congestion())
				.turnStopId(info.turnStopId())
				.gpsX(info.gpsX())
				.gpsY(info.gpsY())
				.isRunYn(info.isRunYn())
				.build();
	}
}
