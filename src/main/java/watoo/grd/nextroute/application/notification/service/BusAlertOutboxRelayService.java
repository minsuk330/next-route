package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.config.TossMessengerProperties;
import watoo.grd.nextroute.application.notification.exception.TossMessengerException;
import watoo.grd.nextroute.application.notification.port.out.TossMessengerPort;
import watoo.grd.nextroute.application.notification.service.BusAlertOutboxStateService.RelayTask;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * outbox relay(Step B, 단일 worker). reclaim → 배치 claim(커밋) → loadTask → 토스 발송(tx 밖) → mark.
 * polling 과 분리돼 외부 토스 지연이 발송 조건 판단 루프에 영향을 주지 않는다. 병렬화는 Step C.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusAlertOutboxRelayService {

    private final BusAlertOutboxStateService stateService;
    private final TossMessengerPort tossMessengerPort;
    private final BusArrivalAlertProperties properties;
    private final TossMessengerProperties messengerProperties;
    private final Clock clock;

    public void relayOnce() {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            stateService.reclaim(now);

            List<Long> claimed = stateService.claimBatch(now, properties.getOutboxRelayBatchSize());
            for (Long id : claimed) {
                try {
                    Optional<RelayTask> task = stateService.loadTask(id);
                    task.ifPresent(this::send);
                } catch (Exception e) {
                    log.warn("[BusAlertOutboxRelay] notification={} 처리 실패: {}", id, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[BusAlertOutboxRelay] relay 실패: {}", e.getMessage(), e);
        }
    }

    private void send(RelayTask task) {
        // 현재 채널은 TOSS 만. 채널 확장 시 task.channel() 로 분기.
        String templateSetCode = messengerProperties.getBusArrivalTemplateSetCode();
        try {
            tossMessengerPort.sendMessage(task.tossUserKey(), templateSetCode, task.context());
            stateService.markSent(task.notificationId());
        } catch (TossMessengerException e) {
            if (e.isPermanent()) {
                log.warn("[BusAlertOutboxRelay] notification={} permanent 실패: {}", task.notificationId(), e.getMessage());
                stateService.markPermanentFailure(task.notificationId(), e.getMessage());
            } else {
                stateService.markTransientFailure(task.notificationId(), e.getMessage());
            }
        }
    }
}
