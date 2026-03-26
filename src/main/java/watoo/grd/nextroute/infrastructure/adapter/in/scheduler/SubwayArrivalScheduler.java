package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.port.in.CollectSubwayArrivalUseCase;
import watoo.grd.nextroute.application.subway.service.SubwayArrivalCache;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.LocalTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayArrivalScheduler {

	private static final LocalTime QUIET_START = LocalTime.of(1, 0);
	private static final LocalTime QUIET_END = LocalTime.of(4, 30);

	private final CollectSubwayArrivalUseCase useCase;
	private final SubwayArrivalCache subwayArrivalCache;
	private final SubwayDataService subwayDataService;

	@Value("${collector.subway-arrival.enabled:true}")
	private boolean enabled;

	private boolean isDuringQuietHours() {
		LocalTime now = LocalTime.now();
		return !now.isBefore(QUIET_START) && now.isBefore(QUIET_END);
	}

	// 1분마다 수집
	@Scheduled(cron = "${collector.subway-arrival.cron}")
	public void trigger() {
		if (!enabled || isDuringQuietHours()) return;
		useCase.execute();
	}

	// 30분마다 flush
	@Scheduled(cron = "${collector.subway-arrival.flush-cron}")
  /// 이부분 트랜젝션 걸어둬야 할 것 같은데
	public void flush() {
		if (!enabled) return;
		List<SubwayArrivalRaw> dirtyRecords = subwayArrivalCache.drainDirty();
		if (dirtyRecords.isEmpty()) return;
		subwayDataService.saveAllArrivals(dirtyRecords);
		log.info("[SubwayArrival] Flushed {} records to DB (cache size: {})",
				dirtyRecords.size(), subwayArrivalCache.size());
	}

	// 매일 05:00 캐시 초기화
	@Scheduled(cron = "${collector.subway-arrival.reset-cron}")
	public void resetCache() {
		flush();
		subwayArrivalCache.clear();
	}
}
