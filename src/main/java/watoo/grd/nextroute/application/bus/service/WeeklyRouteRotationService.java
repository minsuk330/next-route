package watoo.grd.nextroute.application.bus.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.bus.config.RouteRotationProperties;
import watoo.grd.nextroute.application.bus.dto.BusRouteRidershipRank;
import watoo.grd.nextroute.application.bus.dto.BusRouteRidershipRankingResponse;
import watoo.grd.nextroute.domain.bus.entity.RouteRotationState;
import watoo.grd.nextroute.domain.bus.entity.WeeklyTargetRoute;
import watoo.grd.nextroute.domain.bus.repository.RouteRotationStateRepository;
import watoo.grd.nextroute.domain.bus.repository.WeeklyTargetRouteRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 주간 노선 로테이션. 현재 bucket의 다음 30개(top {@code (bucket+1)*30 + 1 .. *30})로
 * weekly_target_route active 집합을 교체한다. 다음 bucket이 비면(랭킹 끝) bucket 0으로 wrap.
 *
 * <p>수집에서 빠진 노선도 누적 학습 모델에 남아 있으면 예측은 계속 제공된다
 * (TransferArrivalEnricher가 PredictionSupportService 기준으로 판정).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyRouteRotationService {

	private final BusRidershipRankingService rankingService;
	private final WeeklyTargetRouteRepository targetRouteRepository;
	private final RouteRotationStateRepository stateRepository;
	private final RouteRotationProperties properties;
	private final Clock clock;

	public record RotationResult(int bucket, int offset, String month, int routeCount, boolean wrapped) {}

	@Transactional
	public RotationResult rotate() {
		String month = properties.getRidershipMonth();
		int limit = properties.getLimit();

		RouteRotationState state = stateRepository.findFirstByDeletedAtIsNullOrderByIdAsc()
				.orElseGet(() -> stateRepository.save(RouteRotationState.builder()
						.currentBucket(0)
						.ridershipMonth(month)
						.rotatedAt(LocalDateTime.now(clock))
						.build()));

		int attemptedBucket = state.getCurrentBucket() + 1;
		BusRouteRidershipRankingResponse ranking =
				rankingService.findTopRoutes(month, limit, attemptedBucket * limit, properties.getPageSize());

		boolean wrapped = false;
		int targetBucket = attemptedBucket;
		Integer totalBuckets = state.getTotalBuckets();
		if (ranking.rankings().isEmpty()) {
			// 랭킹 끝 도달 → bucket 0으로 순환. 비어 있던 bucket 수가 곧 총 bucket 수.
			wrapped = true;
			totalBuckets = attemptedBucket;
			targetBucket = 0;
			ranking = rankingService.findTopRoutes(month, limit, 0, properties.getPageSize());
		}

		if (ranking.rankings().isEmpty()) {
			log.warn("[RouteRotation] empty ranking for month={} — keeping current targets", month);
			return new RotationResult(state.getCurrentBucket(), state.getCurrentBucket() * limit, month, 0, wrapped);
		}

		replaceActiveRoutes(ranking.rankings(), targetBucket, month);
		state.advanceTo(targetBucket, month, totalBuckets, LocalDateTime.now(clock));

		int offset = targetBucket * limit;
		log.info("[RouteRotation] rotated to bucket={} offset={} month={} routes={} wrapped={}",
				targetBucket, offset, month, ranking.rankings().size(), wrapped);
		return new RotationResult(targetBucket, offset, month, ranking.rankings().size(), wrapped);
	}

	private void replaceActiveRoutes(List<BusRouteRidershipRank> ranks, int bucket, String month) {
		List<WeeklyTargetRoute> current = targetRouteRepository.findByActiveTrueAndDeletedAtIsNull();
		current.forEach(WeeklyTargetRoute::deactivate);
		targetRouteRepository.saveAll(current);

		List<WeeklyTargetRoute> next = ranks.stream()
				.map(r -> WeeklyTargetRoute.builder()
						.routeNo(r.routeNo())
						.routeName(r.routeName())
						.rankPosition(r.rank())
						.bucket(bucket)
						.ridershipMonth(month)
						.active(true)
						.build())
				.toList();
		targetRouteRepository.saveAll(next);
	}
}
