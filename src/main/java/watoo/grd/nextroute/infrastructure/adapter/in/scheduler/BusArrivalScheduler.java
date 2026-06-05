package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.port.in.CollectBusArrivalUseCase;

import java.time.LocalTime;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class BusArrivalScheduler {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final CollectBusArrivalUseCase useCase;

	@Value("${collector.bus-arrival.enabled:true}")
	private boolean enabled;

	@Value("${collector.bus-arrival.active-hours.start:03:30}")
	private String activeStart;

	@Value("${collector.bus-arrival.active-hours.end:00:30}")
	private String activeEnd;

	@Scheduled(cron = "${collector.bus-arrival.cron}", zone = "Asia/Seoul")
	public void trigger() {
		if (!enabled) return;
		if (!isActiveHours()) {
			log.debug("[BusArrival] Off-hours, skipping collection");
			return;
		}
		useCase.execute();
	}

	boolean isActiveHours() {
		return isActiveHours(LocalTime.now(KST));
	}

	boolean isActiveHours(LocalTime now) {
		LocalTime start = LocalTime.parse(activeStart);
		LocalTime end = LocalTime.parse(activeEnd);

		if (start.equals(end)) {
			return true;
		}
		if (start.isBefore(end)) {
			return !now.isBefore(start) && now.isBefore(end);
		}
		return !now.isBefore(start) || now.isBefore(end);
	}
}
