package watoo.grd.nextroute.application.bus.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.config.BusCollectorProperties;
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
	private final BusCollectorProperties properties;
	private final BusApiCallBudget budget;

	@Override
	public void execute() {
		List<String> targetNames = properties.getTargetRouteNames();

		if (targetNames.isEmpty()) {
			log.warn("[BusArrival] No target routes configured. Set collector.bus-arrival.target-route-names.");
			return;
		}

		List<BusRoute> matchedRoutes = busDataService.findRoutesByNames(targetNames);
		List<String> routeIds = matchedRoutes.stream()
				.map(BusRoute::getRouteId)
				.toList();

		if (routeIds.isEmpty()) {
			log.warn("[BusArrival] No matching routes found in DB for {}. Run StaticDataLoader first.", targetNames);
			return;
		}

		if (routeIds.size() < targetNames.size()) {
			log.warn("[BusArrival] Only {}/{} target routes found in DB.", routeIds.size(), targetNames.size());
		}

		int dailyBudget = properties.getDailyBudget();
		if (!budget.canMakeCall(dailyBudget)) {
			log.warn("[BusArrival] Daily budget exhausted ({}/{}). Skipping.", budget.getUsed(), dailyBudget);
			return;
		}

		LocalDateTime collectedAt = LocalDateTime.now();
		int totalSaved = 0;

		log.info("[BusArrival] Starting collection for {} target routes (budget: {}/{})",
				routeIds.size(), budget.getUsed(), dailyBudget);

		for (String routeId : routeIds) {
			if (!budget.canMakeCall(dailyBudget)) {
				log.warn("[BusArrival] Budget exhausted mid-cycle ({}/{}). Stopping.", budget.getUsed(), dailyBudget);
				break;
			}

			try {
				List<BusArrivalInfo> items = busApiPort.getArrInfoByRouteAll(routeId);
				budget.recordCall();
				List<BusArrivalRaw> entities = items.stream()
						.map(info -> toEntity(info, collectedAt))
						.toList();
				busDataService.saveAllArrivals(entities);
				totalSaved += entities.size();
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				budget.recordCall();
				return;
			} catch (Exception e) {
				budget.recordCall();
				log.error("[BusArrival] Failed for route {}: {}", routeId, e.getMessage());
			}
		}

		log.info("[BusArrival] Completed. Saved {} records (budget: {}/{})",
				totalSaved, budget.getUsed(), dailyBudget);
	}

	private BusArrivalRaw toEntity(BusArrivalInfo info, LocalDateTime collectedAt) {
		return BusArrivalRaw.builder()
				// 공통
				.collectedAt(collectedAt)
				.routeId(info.routeId())
				.stopId(info.stopId())
				.arsId(info.arsId())
				.seq(info.seq())
				.direction(info.direction())
				.routeType(info.routeType())
				.term(info.term())
				.dataTimestamp(info.dataTimestamp())
				.detourYn(info.detourYn())
				.nextBusYn(info.nextBusYn())
				// 첫 번째 버스
				.arrivalMsg1(info.arrivalMsg1())
				.vehicleId1(info.vehicleId1())
				.plateNo1(info.plateNo1())
				.busType1(info.busType1())
				.sectionOrder1(info.sectionOrder1())
				.stationName1(info.stationName1())
				.isArrive1(info.isArrive1())
				.isLast1(info.isLast1())
				.isFull1(info.isFull1())
				.predictTime1(info.predictTime1())
				.kalPredictTime1(info.kalPredictTime1())
				.neuPredictTime1(info.neuPredictTime1())
				.goalTime1(info.goalTime1())
				.avgCoefficient1(info.avgCoefficient1())
				.expCoefficient1(info.expCoefficient1())
				.kalCoefficient1(info.kalCoefficient1())
				.neuCoefficient1(info.neuCoefficient1())
				.sectionTime1(info.sectionTime1())
				.sectionSpeed1(info.sectionSpeed1())
				.congestionNum1(info.congestionNum1())
				.congestionDiv1(info.congestionDiv1())
				.rideNum1(info.rideNum1())
				.rideDiv1(info.rideDiv1())
				.nextStopId1(info.nextStopId1())
				.nextStopOrd1(info.nextStopOrd1())
				.nextStopSec1(info.nextStopSec1())
				.nextStopSpd1(info.nextStopSpd1())
				.mainStopOrd1(info.mainStopOrd1())
				.mainStopSec1(info.mainStopSec1())
				.mainStopId1(info.mainStopId1())
				.main2StopOrd1(info.main2StopOrd1())
				.main2StopSec1(info.main2StopSec1())
				.main2StopId1(info.main2StopId1())
				.main3StopOrd1(info.main3StopOrd1())
				.main3StopSec1(info.main3StopSec1())
				.main3StopId1(info.main3StopId1())
				// 두 번째 버스
				.arrivalMsg2(info.arrivalMsg2())
				.vehicleId2(info.vehicleId2())
				.plateNo2(info.plateNo2())
				.busType2(info.busType2())
				.sectionOrder2(info.sectionOrder2())
				.stationName2(info.stationName2())
				.isArrive2(info.isArrive2())
				.isLast2(info.isLast2())
				.isFull2(info.isFull2())
				.predictTime2(info.predictTime2())
				.kalPredictTime2(info.kalPredictTime2())
				.neuPredictTime2(info.neuPredictTime2())
				.goalTime2(info.goalTime2())
				.avgCoefficient2(info.avgCoefficient2())
				.expCoefficient2(info.expCoefficient2())
				.kalCoefficient2(info.kalCoefficient2())
				.neuCoefficient2(info.neuCoefficient2())
				.sectionTime2(info.sectionTime2())
				.sectionSpeed2(info.sectionSpeed2())
				.congestionNum2(info.congestionNum2())
				.congestionDiv2(info.congestionDiv2())
				.rideNum2(info.rideNum2())
				.rideDiv2(info.rideDiv2())
				.nextStopId2(info.nextStopId2())
				.nextStopOrd2(info.nextStopOrd2())
				.nextStopSec2(info.nextStopSec2())
				.nextStopSpd2(info.nextStopSpd2())
				.mainStopOrd2(info.mainStopOrd2())
				.mainStopSec2(info.mainStopSec2())
				.mainStopId2(info.mainStopId2())
				.main2StopOrd2(info.main2StopOrd2())
				.main2StopSec2(info.main2StopSec2())
				.main2StopId2(info.main2StopId2())
				.main3StopOrd2(info.main3StopOrd2())
				.main3StopSec2(info.main3StopSec2())
				.main3StopId2(info.main3StopId2())
				.build();
	}
}
