package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.port.in.CollectBusPositionUseCase;

@Component
@RequiredArgsConstructor
public class BusPositionScheduler {

	private final CollectBusPositionUseCase useCase;

	@Value("${collector.bus-position.enabled:true}")
	private boolean enabled;

	@Scheduled(cron = "${collector.bus-position.cron}")
	public void trigger() {
		if (!enabled) return;
		useCase.execute();
	}
}
