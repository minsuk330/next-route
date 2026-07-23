package watoo.grd.nextroute.infrastructure.adapter.in.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusRoutePolylineBackfillResult;
import watoo.grd.nextroute.application.bus.port.in.LoadBusRoutePolylineUseCase;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "batch.bus-route-polyline",
		name = "enabled",
		havingValue = "true"
)
public class BusRoutePolylineBackfillRunner implements ApplicationRunner {

	private final LoadBusRoutePolylineUseCase useCase;

	@Override
	public void run(ApplicationArguments args) {
		BusRoutePolylineBackfillResult result = useCase.execute();
		if (result.aborted()) {
			log.error("[BusRoutePolyline] One-shot run aborted: {}", result);
		} else {
			log.info("[BusRoutePolyline] One-shot run completed: {}", result);
		}
	}
}
