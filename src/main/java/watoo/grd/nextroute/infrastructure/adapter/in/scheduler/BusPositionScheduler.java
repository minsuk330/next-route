package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.port.in.CollectBusPositionUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusApiBlockStatusPort;
import watoo.grd.nextroute.common.config.ClockConfig;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class BusPositionScheduler {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final CollectBusPositionUseCase useCase;
	private final BusApiBlockStatusPort busApiBlockStatusPort;

	@Value("${collector.bus-position.enabled:true}")
	private boolean enabled;

	@Value("${collector.bus-position.active-hours.start:03:30}")
	private String activeStart;

	@Value("${collector.bus-position.active-hours.end:00:30}")
	private String activeEnd;

	@Scheduled(cron = "${collector.bus-position.cron}", zone = "Asia/Seoul")
	public void trigger() {
		if (!enabled) return;
		if (!isActiveHours()) {
			log.debug("[BusPosition] Off-hours, skipping collection");
			return;
		}
		Optional<Instant> blockedUntil = busApiBlockStatusPort.getBlockedUntil();
		if (blockedUntil.isPresent()) {
			log.debug("[BusPosition] API blocked until {}, skipping collection",
					blockedUntil.get().atZone(ClockConfig.KST));
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
