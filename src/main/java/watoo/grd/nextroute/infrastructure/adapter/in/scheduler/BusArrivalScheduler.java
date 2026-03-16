package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.port.in.CollectBusArrivalUseCase;

@Component
@RequiredArgsConstructor
public class BusArrivalScheduler {

	private final CollectBusArrivalUseCase useCase;

	@Value("${collector.bus-arrival.enabled:true}")
	private boolean enabled;

	@Scheduled(cron = "${collector.bus-arrival.cron}")
	public void trigger() {
		if (!enabled) return;
		useCase.execute();
	}
}
