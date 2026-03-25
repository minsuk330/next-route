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

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayArrivalScheduler {

	private final CollectSubwayArrivalUseCase useCase;
	private final SubwayArrivalCache subwayArrivalCache;
	private final SubwayDataService subwayDataService;

	@Value("${collector.subway-arrival.enabled:true}")
	private boolean enabled;

	// 1분마다 수집
	@Scheduled(cron = "${collector.subway-arrival.cron}")
	public void trigger() {
		if (!enabled) return;
		useCase.execute();
	}

	// 30분마다 flush
	@Scheduled(cron = "${collector.subway-arrival.flush-cron}")
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
