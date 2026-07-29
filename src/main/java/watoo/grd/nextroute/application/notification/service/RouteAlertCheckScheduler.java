package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 체크포인트 스케줄러(flag: notification.route-arrival.scheduler-enabled).
 * 활성 + next_action_at 도래 alert 를 스캔해 단건 처리기에 위임한다. WAITING_DEPARTURE 는 next_action_at=NULL 이라 제외.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("worker")
@ConditionalOnProperty(prefix = "notification.route-arrival", name = "scheduler-enabled", havingValue = "true")
public class RouteAlertCheckScheduler {

    private final RouteArrivalAlertRepository alertRepository;
    private final RouteAlertCheckProcessor processor;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${notification.route-arrival.check-interval-ms:30000}",
            scheduler = "notificationTaskScheduler")
    public void run() {
        LocalDateTime now = LocalDateTime.now(clock);
        // 체크 도래(next_action_at) + 만료 도래(expires_at) 합집합. 후자는 WAITING_DEPARTURE
        // 고아(발송 취소·영구실패로 nextActionAt=NULL 고착)까지 정리한다.
        Set<Long> ids = new LinkedHashSet<>();
        alertRepository.findByStatusAndNextActionAtLessThanEqualAndDeletedAtIsNull(RouteAlertStatus.ACTIVE, now)
                .forEach(a -> ids.add(a.getId()));
        alertRepository.findByStatusAndExpiresAtLessThanEqualAndDeletedAtIsNull(RouteAlertStatus.ACTIVE, now)
                .forEach(a -> ids.add(a.getId()));
        if (ids.isEmpty()) return;

        int ok = 0, failed = 0;
        for (Long id : ids) {
            try {
                processor.process(id);   // process() 가 만료 우선 처리 후 phase 분기
                ok++;
            } catch (Exception e) {
                // 개별 alert 실패 격리(낙관락 충돌 포함) — 다음 스캔에 재시도.
                failed++;
                log.warn("[RouteAlertCheck] alert={} 처리 실패: {}", id, e.getMessage());
            }
        }
        log.debug("[RouteAlertCheck] scanned={} ok={} failed={}", ids.size(), ok, failed);
    }
}
