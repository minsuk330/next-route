package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.port.in.LoadBusStaticDataUseCase;
import watoo.grd.nextroute.application.subway.port.in.LoadSubwayStaticDataUseCase;

@Slf4j
@Component
@RequiredArgsConstructor
public class StaticDataScheduler {

	private final LoadBusStaticDataUseCase loadBusStaticData;
	private final LoadSubwayStaticDataUseCase loadSubwayStaticData;

	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		log.info("[StaticData] Loading static data on startup...");
		loadBusStaticData.execute();
		loadSubwayStaticData.execute();
	}

	@Scheduled(cron = "${collector.static-data.cron}")
	public void scheduledLoad() {
		log.info("[StaticData] Daily reload starting...");
		loadBusStaticData.execute();
		loadSubwayStaticData.execute();
	}
}
