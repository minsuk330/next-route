package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.notification.config.RouteArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.config.TossMessengerProperties;
import watoo.grd.nextroute.application.notification.exception.TossMessengerException;
import watoo.grd.nextroute.application.notification.port.out.TossMessengerPort;
import watoo.grd.nextroute.application.notification.service.RouteAlertDispatchStateService.DispatchTask;
import watoo.grd.nextroute.domain.notification.entity.RouteNotificationType;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 발송 디스패처(flag: notification.route-arrival.dispatch-enabled). 외부 토스 호출은 트랜잭션 밖.
 * reclaim → bounded 로드 → (stale 가드+claim) → send → mark. DEPARTURE 발송성공은 부모 alert 전이와 원자.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "notification.route-arrival", name = "dispatch-enabled", havingValue = "true")
public class RouteAlertDispatchScheduler {

    private final RouteAlertDispatchStateService stateService;
    private final TossMessengerPort tossMessengerPort;
    private final RouteArrivalAlertProperties properties;
    private final TossMessengerProperties messengerProperties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${notification.route-arrival.dispatch-interval-ms:15000}")
    public void run() {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            stateService.reclaim(now);

            // 배치 원자 claim(cancelStale + SKIP LOCKED) 은 한 트랜잭션에서 커밋된 뒤 id 만 넘어온다.
            List<Long> claimedIds = stateService.claimBatch(now, properties.getDispatchBatchSize());

            for (Long id : claimedIds) {
                try {
                    Optional<DispatchTask> task = stateService.loadTask(id);
                    task.ifPresent(this::send);
                } catch (Exception e) {
                    // 개별 격리(낙관락 충돌 포함) — reclaim 후 다음 스캔 재시도.
                    log.warn("[RouteAlertDispatch] notification={} 처리 실패: {}", id, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[RouteAlertDispatch] 디스패치 실패: {}", e.getMessage(), e);
        }
    }

    private void send(DispatchTask task) {
        String templateCode = task.type() == RouteNotificationType.DEPARTURE
                ? messengerProperties.getRouteDepartureTemplateSetCode()
                : messengerProperties.getRouteChangeTemplateSetCode();
        try {
            tossMessengerPort.sendMessage(task.tossUserKey(), templateCode, task.context());
            if (task.type() == RouteNotificationType.DEPARTURE) {
                stateService.markDepartureSent(task.notificationId());
            } else {
                stateService.markChangeSent(task.notificationId());
            }
        } catch (TossMessengerException e) {
            if (e.isPermanent()) {
                log.warn("[RouteAlertDispatch] notification={} permanent 실패: {}", task.notificationId(), e.getMessage());
                stateService.markPermanentFailure(task.notificationId(), e.getMessage());
            } else {
                stateService.markTransientFailure(task.notificationId(), e.getMessage());
            }
        }
    }
}
