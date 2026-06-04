package watoo.grd.nextroute.application.bus.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.config.BusCollectorProperties;
import watoo.grd.nextroute.application.bus.dto.BusArrivalActiveSnapshot;
import watoo.grd.nextroute.application.bus.dto.BusArrivalCandidate;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.port.in.CollectBusArrivalUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.application.bus.port.out.BusArrivalSnapshotPort;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalCandidateRaw;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusArrivalService implements CollectBusArrivalUseCase {

	private final BusApiPort busApiPort;
	private final BusDataService busDataService;
	private final BusCollectorProperties properties;
	private final BusApiCallBudget budget;
	private final BusArrivalSnapshotPort busArrivalSnapshotPort;

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
		int totalFinalized = 0;

		log.info("[BusArrival] Starting collection for {} target routes (budget: {}/{})",
				routeIds.size(), budget.getUsed(), dailyBudget);

		for (String routeId : routeIds) {
			if (!budget.canMakeCall(dailyBudget)) {
				log.warn("[BusArrival] Budget exhausted mid-cycle ({}/{}). Stopping.", budget.getUsed(), dailyBudget);
				break;
			}

			boolean callRecorded = false;
			try {
				List<BusArrivalInfo> items = busApiPort.getArrInfoByRouteAll(routeId);
				budget.recordCall();
				callRecorded = true;
				totalFinalized += reconcile(items, collectedAt);
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				if (!callRecorded) {
					budget.recordCall();
				}
				return;
			} catch (Exception e) {
				if (!callRecorded) {
					budget.recordCall();
				}
				log.error("[BusArrival] Failed for route {}: {}", routeId, e.getMessage());
			}
		}

		log.info("[BusArrival] Completed. Finalized {} candidates (budget: {}/{})",
				totalFinalized, budget.getUsed(), dailyBudget);
	}

	private int reconcile(List<BusArrivalInfo> items, LocalDateTime collectedAt) {
		int finalized = 0;
		for (Map.Entry<ArrivalScope, List<BusArrivalCandidate>> entry : groupCandidates(items, collectedAt).entrySet()) {
			finalized += reconcile(entry.getKey(), entry.getValue(), collectedAt);
		}
		return finalized;
	}

	private Map<ArrivalScope, List<BusArrivalCandidate>> groupCandidates(
			List<BusArrivalInfo> items,
			LocalDateTime collectedAt
	) {
		Map<ArrivalScope, List<BusArrivalCandidate>> candidatesByScope = new LinkedHashMap<>();
		for (BusArrivalInfo info : items) {
			ArrivalScope scope = ArrivalScope.from(info);
			if (scope == null) {
				log.debug("[BusArrival] Skipping row without route/stop/seq scope: routeId={}, stopId={}, seq={}",
						info.routeId(), info.stopId(), info.seq());
				continue;
			}
			candidatesByScope.computeIfAbsent(scope, ignored -> new ArrayList<>())
					.addAll(BusArrivalCandidate.from(info, collectedAt));
		}
		return candidatesByScope;
	}

	private int reconcile(ArrivalScope scope, List<BusArrivalCandidate> candidates, LocalDateTime finalizedAt) {
		Map<String, BusArrivalActiveSnapshot> previousSnapshots =
				busArrivalSnapshotPort.findActive(scope.routeId(), scope.stopId(), scope.seq());
		Map<String, BusArrivalCandidate> currentCandidates = deduplicateByIdentity(candidates);

		for (Map.Entry<String, BusArrivalCandidate> entry : currentCandidates.entrySet()) {
			BusArrivalActiveSnapshot previous = previousSnapshots.get(entry.getKey());
			busArrivalSnapshotPort.save(BusArrivalActiveSnapshot.from(entry.getValue(), previous));
		}

		List<String> missingIdentityKeys = previousSnapshots.keySet().stream()
				.filter(identityKey -> !currentCandidates.containsKey(identityKey))
				.toList();
		if (missingIdentityKeys.isEmpty()) {
			return 0;
		}

		List<BusArrivalCandidateRaw> finalizedCandidates = missingIdentityKeys.stream()
				.map(previousSnapshots::get)
				.map(snapshot -> toEntity(snapshot, finalizedAt))
				.toList();
		busDataService.saveAllArrivalCandidates(finalizedCandidates);

		for (String identityKey : missingIdentityKeys) {
			busArrivalSnapshotPort.delete(scope.routeId(), scope.stopId(), scope.seq(), identityKey);
		}

		return finalizedCandidates.size();
	}

	private Map<String, BusArrivalCandidate> deduplicateByIdentity(List<BusArrivalCandidate> candidates) {
		Map<String, BusArrivalCandidate> deduplicated = new LinkedHashMap<>();
		for (BusArrivalCandidate candidate : candidates) {
			deduplicated.putIfAbsent(candidate.identityKey(), candidate);
		}
		return deduplicated;
	}

	private BusArrivalCandidateRaw toEntity(BusArrivalActiveSnapshot snapshot, LocalDateTime finalizedAt) {
		BusArrivalCandidate candidate = snapshot.candidate();
		return BusArrivalCandidateRaw.builder()
				.collectedAt(candidate.collectedAt())
				.finalizedAt(finalizedAt)
				.firstSeenAt(snapshot.firstSeenAt())
				.lastSeenAt(snapshot.lastSeenAt())
				.lastCollectedAt(snapshot.lastCollectedAt())
				.routeId(candidate.routeId())
				.routeAbrv(candidate.routeAbrv())
				.routeName(candidate.routeName())
				.stopId(candidate.stopId())
				.arsId(candidate.arsId())
				.stopName(candidate.stopName())
				.seq(candidate.seq())
				.direction(candidate.direction())
				.routeType(candidate.routeType())
				.term(candidate.term())
				.dataTimestamp(candidate.dataTimestamp())
				.detourYn(candidate.detourYn())
				.nextBusYn(candidate.nextBusYn())
				.firstBusTime(candidate.firstBusTime())
				.lastBusTime(candidate.lastBusTime())
				.arrivalOrder(candidate.arrivalOrder())
				.arrivalMsg(candidate.arrivalMsg())
				.vehicleId(candidate.vehicleId())
				.plainNo(candidate.plainNo())
				.vehicleIdentity(candidate.vehicleIdentity())
				.vehicleIdentityType(candidate.vehicleIdentityType().name())
				.busType(candidate.busType())
				.sectionOrder(candidate.sectionOrder())
				.stationName(candidate.stationName())
				.isArrive(candidate.isArrive())
				.isLast(candidate.isLast())
				.isFull(candidate.isFull())
				.predictTime(candidate.predictTime())
				.kalPredictTime(candidate.kalPredictTime())
				.neuPredictTime(candidate.neuPredictTime())
				.goalTime(candidate.goalTime())
				.avgCoefficient(candidate.avgCoefficient())
				.expCoefficient(candidate.expCoefficient())
				.kalCoefficient(candidate.kalCoefficient())
				.neuCoefficient(candidate.neuCoefficient())
				.sectionTime(candidate.sectionTime())
				.sectionSpeed(candidate.sectionSpeed())
				.congestionNum(candidate.congestionNum())
				.congestionDiv(candidate.congestionDiv())
				.rideNum(candidate.rideNum())
				.rideDiv(candidate.rideDiv())
				.nextStopId(candidate.nextStopId())
				.nextStopOrd(candidate.nextStopOrd())
				.nextStopSec(candidate.nextStopSec())
				.nextStopSpd(candidate.nextStopSpd())
				.mainStopOrd(candidate.mainStopOrd())
				.mainStopSec(candidate.mainStopSec())
				.mainStopId(candidate.mainStopId())
				.main2StopOrd(candidate.main2StopOrd())
				.main2StopSec(candidate.main2StopSec())
				.main2StopId(candidate.main2StopId())
				.main3StopOrd(candidate.main3StopOrd())
				.main3StopSec(candidate.main3StopSec())
				.main3StopId(candidate.main3StopId())
				.build();
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}

	private static String normalize(String value) {
		return value == null ? null : value.trim();
	}

	private record ArrivalScope(String routeId, String stopId, Integer seq) {

		private static ArrivalScope from(BusArrivalInfo info) {
			if (!hasText(info.routeId()) || !hasText(info.stopId()) || info.seq() == null) {
				return null;
			}
			return new ArrivalScope(normalize(info.routeId()), normalize(info.stopId()), info.seq());
		}
	}
}
