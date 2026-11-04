package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.port.in.CollectBusArrivalUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusApiBlockStatusPort;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.common.config.ClockConfig;
import watoo.grd.nextroute.common.lock.LeaderLease;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class BusArrivalScheduler {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final CollectBusArrivalUseCase useCase;
	private final BusApiBlockStatusPort busApiBlockStatusPort;
	private final LeaderLease leaderLease;

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
		Optional<Instant> blockedUntil = busApiBlockStatusPort.getBlockedUntil(BusApiQuotaPort.Endpoint.ARRIVAL);
		if (blockedUntil.isPresent()) {
			log.debug("[BusArrival] API blocked until {}, skipping collection",
					blockedUntil.get().atZone(ClockConfig.KST));
			return;
		}
		// 공급자를 부르는 수집은 리더 한 대만(잠금 꺼짐이면 늘 리더). worker 가 여러 대여도 노선당 1분에 1회.
		if (!leaderLease.isLeader()) {
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
