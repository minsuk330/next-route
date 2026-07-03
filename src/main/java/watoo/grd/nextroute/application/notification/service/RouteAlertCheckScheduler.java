package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 체크포인트 스케줄러(flag: notification.route-arrival.scheduler-enabled).
 * 활성 + next_action_at 도래 alert 를 스캔해 단건 처리기에 위임한다. WAITING_DEPARTURE 는 next_action_at=NULL 이라 제외.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "notification.route-arrival", name = "scheduler-enabled", havingValue = "true")
public class RouteAlertCheckScheduler {

    private final RouteArrivalAlertRepository alertRepository;
    private final RouteAlertCheckProcessor processor;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${notification.route-arrival.check-interval-ms:30000}")
    public void run() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<RouteArrivalAlert> due = alertRepository
                .findByStatusAndNextActionAtLessThanEqualAndDeletedAtIsNull(RouteAlertStatus.ACTIVE, now);
        if (due.isEmpty()) return;

        int ok = 0, failed = 0;
        for (RouteArrivalAlert alert : due) {
            try {
                processor.process(alert.getId());
                ok++;
            } catch (Exception e) {
                // 개별 alert 실패 격리(낙관락 충돌 포함) — 다음 스캔에 재시도.
                failed++;
                log.warn("[RouteAlertCheck] alert={} 처리 실패: {}", alert.getId(), e.getMessage());
            }
        }
        log.debug("[RouteAlertCheck] scanned={} ok={} failed={}", due.size(), ok, failed);
    }
}
