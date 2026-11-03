package watoo.grd.nextroute.application.bus.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.exception.BusApiBlockedException;
import watoo.grd.nextroute.application.bus.port.in.CollectBusPositionUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheWritePort;
import watoo.grd.nextroute.domain.bus.entity.BusPositionRaw;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.repository.BusPositionRawRepository;
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.common.lock.LeaderLease;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusPositionService implements CollectBusPositionUseCase {

	private static final long API_CALL_INTERVAL_MS = 100;
	/** 수집 몫 Redis 버킷 — 검색 몫(position)과 이름이 다르다. 서버 수와 무관하게 하나로 센다. */
	static final String BUCKET_POSITION = "collector-position";

	private final BusApiPort busApiPort;
	private final BusRouteRepository busRouteRepository;
	private final BusPositionRawRepository busPositionRawRepository;
	private final TargetRouteProvider targetRouteProvider;
	private final BusApiQuotaPort quota;
	private final LeaderLease leaderLease;
	private final Clock clock;
	private final BusRealtimeCacheWritePort realtimeCacheWrite;

	@Value("${collector.bus-position.daily-budget:50000}")
	private int dailyBudget = 50000;

	/** 수집한 위치를 실시간 캐시에 프라이밍할지(노선당 1키, 저렴 — 기본 on). */
	@Value("${collector.bus-position.prime-realtime-cache:true}")
	private boolean primeRealtimeCache = true;

	@Override
	public void execute() {
		List<String> targetNames = targetRouteProvider.activeRouteNames();

		if (targetNames.isEmpty()) {
			log.warn("[BusPosition] No target routes configured. Set collector.bus-arrival.target-route-names.");
			return;
		}

		List<BusRoute> matchedRoutes = busRouteRepository.findByRouteNameIn(targetNames);
		List<String> routeIds = matchedRoutes.stream()
				.map(BusRoute::getRouteId)
				.toList();

		if (routeIds.isEmpty()) {
			log.warn("[BusPosition] No matching routes found in DB for {}. Run StaticDataLoader first.", targetNames);
			return;
		}

//		if (routeIds.size() < targetNames.size()) {
//			log.warn("[BusPosition] Only {}/{} target routes found in DB.", routeIds.size(), targetNames.size());
//		}

		LocalDateTime collectedAt = LocalDateTime.now(clock);
		int totalSaved = 0;
		int successfulRoutes = 0;
		int failedRoutes = 0;

		for (String routeId : routeIds) {
			// 리더 재확인이 몫 점유보다 앞(리더가 아닌 서버가 몫을 태우지 않게). 잠금이 꺼져 있으면 늘 리더다.
			if (!leaderLease.isLeader()) {
				log.info("[BusPosition] Lost leadership. Stopping before route {}.", routeId);
				return;
			}
			// 몫은 Redis 버킷(서버 수와 무관). 호출 전 점유라 실패한 호출도 1 로 센다. Redis 장애면 fail-closed.
			if (!quota.tryAcquire(BUCKET_POSITION, dailyBudget)) {
				log.warn("[BusPosition] Daily budget exhausted or unavailable (limit {}). Stopping before route {}.",
						dailyBudget, routeId);
				break;
			}

			try {
				List<BusPositionInfo> items = busApiPort.getBusPosByRtid(routeId);

				List<BusPositionRaw> entities = items.stream()
						.map(info -> toEntity(info, routeId, collectedAt))
						.toList();
				if (!entities.isEmpty()) {
					busPositionRawRepository.saveAll(entities);
					totalSaved += entities.size();
				}
				// 검색·공용 조회가 재사용하도록 실시간 캐시 프라이밍(best-effort — 어댑터가 장애 graceful 처리)
				if (primeRealtimeCache && !items.isEmpty()) {
					realtimeCacheWrite.putPositions(routeId, items);
				}
				successfulRoutes++;
				Thread.sleep(API_CALL_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (BusApiBlockedException e) {
				log.warn("[BusPosition] API blocked. Stopping collection: {}", e.getMessage());
				return;
			} catch (Exception e) {
				failedRoutes++;
				log.error("[BusPosition] Failed for route {}: {}", routeId, e.getMessage());
			}
		}

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
