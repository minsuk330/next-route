package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.config.RouteRotationProperties;
import watoo.grd.nextroute.application.bus.service.WeeklyRouteRotationService;

/**
 * 주간 노선 로테이션 트리거. {@code collector.route-rotation.enabled=true} 일 때만 동작.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyRouteRotationScheduler {

	private final WeeklyRouteRotationService rotationService;
	private final RouteRotationProperties properties;

	@Scheduled(cron = "${collector.route-rotation.cron}", zone = "Asia/Seoul")
	public void trigger() {
		if (!properties.isEnabled()) {
			log.debug("[RouteRotation] disabled, skipping");
			return;
		}
		try {
			rotationService.rotate();
		} catch (Exception e) {
			log.error("[RouteRotation] rotation failed", e);
		}
	}
}
