package watoo.grd.nextroute.application.bus.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.config.BusCollectorProperties;
import watoo.grd.nextroute.application.bus.dto.ArrivalScope;
import watoo.grd.nextroute.application.bus.dto.BusArrivalActiveSnapshot;
import watoo.grd.nextroute.application.bus.dto.BusArrivalCandidate;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.port.in.CollectBusArrivalUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.application.bus.port.out.BusArrivalSnapshotPort;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalCandidateRaw;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusArrivalService implements CollectBusArrivalUseCase {

  private static final long API_CALL_INTERVAL_MS = 100;

	private final BusApiPort busApiPort;
	private final BusDataService busDataService;
	private final BusCollectorProperties properties;
	@Qualifier("arrivalApiCallBudget")
	private final BusApiCallBudget budget;
	private final BusArrivalSnapshotPort busArrivalSnapshotPort;
	private final Clock clock;

	@Override
	public void execute() {
		List<String> targetNames = properties.getTargetRouteNames(); ///이게routename이다.

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

		LocalDateTime collectedAt = LocalDateTime.now(clock);
		int totalFinalized = 0;

		log.info("[BusArrival] Starting collection for {} target routes (budget: {}/{})",
				routeIds.size(), budget.getUsed(), dailyBudget);

		for (String routeId : routeIds) {
			// 예산이 소진돼 API를 못 불러도 stale active purge는 멈추지 않는다.
			// (API 호출과 무관하게 오래 끊긴 active를 정리해야 유령 레코드/누수가 누적되지 않는다)
			if (!budget.canMakeCall(dailyBudget)) {
				log.warn("[BusArrival] Budget exhausted ({}/{}). Skipping API for route {}, stale purge only.",
						budget.getUsed(), dailyBudget, routeId);
				purgeStaleActive(routeId, collectedAt);
				continue;
			}

			boolean callRecorded = false;
			try {
				List<BusArrivalInfo> items = busApiPort.getArrInfoByRouteAll(routeId);
				budget.recordCall();
				callRecorded = true;
				totalFinalized += reconcile(routeId, items, collectedAt);
				Thread.sleep(API_CALL_INTERVAL_MS);
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

	private int reconcile(String routeId, List<BusArrivalInfo> items, LocalDateTime collectedAt) {
		Map<ArrivalScope, List<BusArrivalCandidate>> grouped = groupCandidates(items, collectedAt);

		// API 응답에 있는 scope ∪ Redis에 살아 있는 scope.
		// 응답에서 통째로 빠진 route-stop-seq도 reconcile해 missedCount/stale 처리가 돌게 한다.
		Set<ArrivalScope> scopes = new LinkedHashSet<>(grouped.keySet());
		scopes.addAll(busArrivalSnapshotPort.findActiveScopes(routeId));

		int finalized = 0;
		for (ArrivalScope scope : scopes) {
			finalized += reconcile(scope, grouped.getOrDefault(scope, List.of()), collectedAt);
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
					.addAll(BusArrivalCandidate.from(info, collectedAt).stream()
							.filter(this::shouldCollect)
							.toList());
		}
		return candidatesByScope;
	}

	private int reconcile(ArrivalScope scope, List<BusArrivalCandidate> candidates, LocalDateTime finalizedAt) {
		Map<String, BusArrivalActiveSnapshot> allPreviousSnapshots =
				busArrivalSnapshotPort.findActive(scope.routeId(), scope.stopId(), scope.seq());
		Map<String, BusArrivalActiveSnapshot> previousSnapshots = collectablePreviousSnapshots(
				scope,
				allPreviousSnapshots
		);
		Map<String, BusArrivalCandidate> currentCandidates = deduplicateByIdentity(candidates);

		for (Map.Entry<String, BusArrivalCandidate> entry : currentCandidates.entrySet()) {
			BusArrivalActiveSnapshot previous = previousSnapshots.get(entry.getKey());
			busArrivalSnapshotPort.save(BusArrivalActiveSnapshot.from(entry.getValue(), previous));
		}

		List<String> missingIdentityKeys = previousSnapshots.keySet().stream()
				.filter(identityKey -> !currentCandidates.containsKey(identityKey))
				.toList();

		List<BusArrivalCandidateRaw> finalizedCandidates = new ArrayList<>();
		List<String> finalizedIdentityKeys = new ArrayList<>();

		for (String identityKey : missingIdentityKeys) {
			BusArrivalActiveSnapshot previous = previousSnapshots.get(identityKey);

			// 너무 오래 끊긴 active(예: off-hours 공백을 넘긴 전날 잔여)는 finalize하지 않고 폐기한다.
			// firstSeenAt~lastSeenAt이 어제로 박힌 유령 레코드가 DB에 들어가는 것을 막는다.
			if (isStale(previous, finalizedAt)) {
				busArrivalSnapshotPort.delete(scope.routeId(), scope.stopId(), scope.seq(), identityKey);
				log.debug("[BusArrival] Discarding stale active snapshot: scope={}, key={}, lastCollectedAt={}",
						scope, identityKey, previous.lastCollectedAt());
				continue;
			}

			BusArrivalActiveSnapshot missingSnapshot = previous.markMissing();
			if (missingSnapshot.currentMissedCount() >= finalizeMissThreshold()) {
				finalizedCandidates.add(toEntity(missingSnapshot, finalizedAt));
				finalizedIdentityKeys.add(identityKey);
			} else {
				busArrivalSnapshotPort.save(missingSnapshot);
			}
		}

		if (!finalizedCandidates.isEmpty()) {
			busDataService.saveAllArrivalCandidates(finalizedCandidates);
			for (String identityKey : finalizedIdentityKeys) {
				busArrivalSnapshotPort.delete(scope.routeId(), scope.stopId(), scope.seq(), identityKey);
			}
		}

		busArrivalSnapshotPort.cleanupScopeIfEmpty(scope);
		return finalizedCandidates.size();
	}

	private boolean isStale(BusArrivalActiveSnapshot snapshot, LocalDateTime now) {
		LocalDateTime lastCollectedAt = snapshot.lastCollectedAt();
		if (lastCollectedAt == null) {
			return false;
		}
		return Duration.between(lastCollectedAt, now).toMinutes() >= staleThresholdMinutes();
	}

	/**
	 * 예산 소진 등으로 API를 못 부르는 사이클에서도 도는 stale purge 전용 경로.
	 * API 응답이 없으므로 실제 미관측 여부를 알 수 없어 markMissing/finalize는 하지 않고,
	 * 오래 끊긴(stale) active만 폐기한다.
	 */
	private void purgeStaleActive(String routeId, LocalDateTime now) {
		for (ArrivalScope scope : busArrivalSnapshotPort.findActiveScopes(routeId)) {
			Map<String, BusArrivalActiveSnapshot> active =
					busArrivalSnapshotPort.findActive(scope.routeId(), scope.stopId(), scope.seq());
			for (Map.Entry<String, BusArrivalActiveSnapshot> entry : active.entrySet()) {
				if (isStale(entry.getValue(), now)) {
					busArrivalSnapshotPort.delete(scope.routeId(), scope.stopId(), scope.seq(), entry.getKey());
					log.debug("[BusArrival] Budget-off stale discard: scope={}, key={}", scope, entry.getKey());
				}
			}
			busArrivalSnapshotPort.cleanupScopeIfEmpty(scope);
		}
	}

	private Map<String, BusArrivalActiveSnapshot> collectablePreviousSnapshots(
			ArrivalScope scope,
			Map<String, BusArrivalActiveSnapshot> previousSnapshots
	) {
		Map<String, BusArrivalActiveSnapshot> collectable = new LinkedHashMap<>();
		for (Map.Entry<String, BusArrivalActiveSnapshot> entry : previousSnapshots.entrySet()) {
			BusArrivalActiveSnapshot snapshot = entry.getValue();
			if (snapshot != null && shouldCollect(snapshot.candidate())) {
				collectable.put(entry.getKey(), snapshot);
			} else {
				busArrivalSnapshotPort.delete(scope.routeId(), scope.stopId(), scope.seq(), entry.getKey());
			}
		}
		return collectable;
	}

	private Map<String, BusArrivalCandidate> deduplicateByIdentity(List<BusArrivalCandidate> candidates) {
		Map<String, BusArrivalCandidate> deduplicated = new LinkedHashMap<>();
		for (BusArrivalCandidate candidate : candidates) {
			deduplicated.putIfAbsent(candidate.identityKey(), candidate);
		}
		return deduplicated;
	}

	private boolean shouldCollect(BusArrivalCandidate candidate) {
		if (candidate == null) {
			return false;
		}

		List<Integer> includedOrders = includedArrivalOrders();
		if (!includedOrders.isEmpty() && !includedOrders.contains(candidate.arrivalOrder())) {
			return false;
		}

		String arrivalMsg = normalize(candidate.arrivalMsg());
		return excludedArrivalMessages().stream()
				.map(BusArrivalService::normalize)
				.filter(Objects::nonNull)
				.noneMatch(excluded -> excluded.equals(arrivalMsg));
	}

	private int finalizeMissThreshold() {
		int configured = properties.getFinalizeMissThreshold();
		return configured <= 0 ? 2 : configured;
	}

	private int staleThresholdMinutes() {
		int configured = properties.getStaleThresholdMinutes();
		return configured <= 0 ? 5 : configured;
	}

	private List<Integer> includedArrivalOrders() {
		List<Integer> configured = properties.getIncludedArrivalOrders();
		if (configured == null || configured.isEmpty()) {
			return List.of(1);
		}
		return configured.stream()
				.filter(Objects::nonNull)
				.toList();
	}

	private List<String> excludedArrivalMessages() {
		List<String> configured = properties.getExcludedArrivalMessages();
		if (configured == null || configured.isEmpty()) {
			return List.of("출발대기");
		}
		return configured;
	}

	private BusArrivalCandidateRaw toEntity(BusArrivalActiveSnapshot snapshot, LocalDateTime finalizedAt) {
		BusArrivalCandidate candidate = snapshot.candidate();
		return BusArrivalCandidateRaw.builder()
				.lifecycleId(snapshot.lifecycleId())
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

	private static String normalize(String value) {
		return value == null ? null : value.trim();
	}
}
