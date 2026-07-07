package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertSchedule;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertScheduleStatus;
import watoo.grd.nextroute.domain.notification.repository.RouteAlertScheduleRepository;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 반복/일회성 구독 spawner(flag: notification.route-arrival.schedule-spawner-enabled, 기본 off).
 * ACTIVE schedule 을 스캔해 schedule별 독립 트랜잭션({@link RouteAlertScheduleSpawnService})으로 위임한다.
 * 유니크 충돌은 여기서 멱등 skip 로 흡수(다중 인스턴스/재시도 안전).
 * <p>flag key 는 check scheduler(scheduler-enabled)·dispatcher(dispatch-enabled)와 별개다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "notification.route-arrival", name = "schedule-spawner-enabled", havingValue = "true")
public class RouteAlertScheduleSpawner {

    private final RouteAlertScheduleRepository scheduleRepository;
    private final RouteAlertScheduleSpawnService spawnService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${notification.route-arrival.schedule-spawn-interval-ms:60000}")
    public void run() {
        LocalDateTime now = LocalDateTime.now(clock);
        for (RouteAlertSchedule s : scheduleRepository
                .findByStatusAndDeletedAtIsNull(RouteAlertScheduleStatus.ACTIVE)) {
            try {
                spawnService.processScheduleSpawn(s.getId(), now);
            } catch (DataIntegrityViolationException dup) {
                // 유니크 (schedule_id, target_arrival_at) 충돌: 이미 생성됨 → idempotent skip.
                log.debug("[RouteAlertSpawn] duplicate occurrence (idempotent) scheduleId={}", s.getId());
            } catch (Exception e) {
                // @Version 경합 등: 다음 스캔서 재시도.
                log.warn("[RouteAlertSpawn] schedule {} spawn failed: {}", s.getId(), e.getMessage());
            }
        }
    }
}
