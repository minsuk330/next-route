package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.port.in.CollectSubwayArrivalUseCase;

@Component
@RequiredArgsConstructor
public class SubwayArrivalScheduler {

	private final CollectSubwayArrivalUseCase useCase;

	@Value("${collector.subway-arrival.enabled:true}")
	private boolean enabled;

	@Scheduled(cron = "${collector.subway-arrival.cron}")
	public void trigger() {
		if (!enabled) return;
		useCase.execute();
	}
}
