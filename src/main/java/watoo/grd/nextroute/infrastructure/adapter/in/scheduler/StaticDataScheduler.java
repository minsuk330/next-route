package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.port.in.LoadBusStaticDataUseCase;
import watoo.grd.nextroute.application.subway.port.in.LoadSubwayStaticDataUseCase;
import watoo.grd.nextroute.domain.bus.service.BusDataService;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

@Slf4j
@Component
@RequiredArgsConstructor
public class StaticDataScheduler {

	private final LoadBusStaticDataUseCase loadBusStaticData;
	private final LoadSubwayStaticDataUseCase loadSubwayStaticData;
	private final BusDataService busDataService;
	private final SubwayDataService subwayDataService;

	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (busDataService.findAllRoutes().isEmpty()) {
			log.info("[StaticData] No bus routes in DB. Loading bus master data...");
			loadBusStaticData.execute();
		} else {
			log.info("[StaticData] Bus routes already loaded. Skipping.");
		}

		if (subwayDataService.findAllStations().isEmpty()) {
			log.info("[StaticData] No subway stations in DB. Loading subway master data...");
			loadSubwayStaticData.execute();
		} else {
			log.info("[StaticData] Subway stations already loaded. Skipping.");
		}
	}

	@Scheduled(cron = "${collector.static-data.cron}")
	public void scheduledLoad() {
		log.info("[StaticData] Daily reload starting...");
		loadBusStaticData.execute();
		loadSubwayStaticData.execute();
	}
}
