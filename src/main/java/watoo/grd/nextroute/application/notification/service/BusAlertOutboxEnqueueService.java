package watoo.grd.nextroute.application.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.notification.entity.BusArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.BusAlertNotificationRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * outbox enqueue: 구독 원자 claim + outbox INSERT 를 단일 SQL 로 수행(send-after-commit).
 * polling 은 이 서비스만 호출하고 Toss 는 부르지 않는다 — 발송은 relay 소관.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusAlertOutboxEnqueueService {

    /** 현재 채널/이벤트(다중 채널 확장 시 파라미터화). */
    static final String CHANNEL_TOSS = "TOSS";
    static final String EVENT_ARRIVAL = "ARRIVAL";

    private final BusAlertNotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /** TOSS/ARRIVAL enqueue. 성공(구독 PENDING→PROCESSING + outbox INSERT) 시 true, 경쟁서 짐/중복이면 false. */
    @Transactional
    public boolean enqueueTossArrival(BusArrivalAlert alert, int arrivalMin) {
        String dedupKey = "BUS_ARRIVAL:" + alert.getId() + ":" + EVENT_ARRIVAL + ":" + CHANNEL_TOSS;
        String payload = writePayload(alert, arrivalMin);
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> created = notificationRepository.enqueue(
                alert.getId(), dedupKey, CHANNEL_TOSS, EVENT_ARRIVAL, payload, now);
        if (created.isEmpty()) return false;
        // 커밋 후 relay 즉시 wake(AFTER_COMMIT). 이 tx 에 바인딩되므로 롤백 시 발행 안 됨.
        eventPublisher.publishEvent(new OutboxEnqueuedEvent(created.get(0)));
        return true;
    }

    private String writePayload(BusArrivalAlert alert, int arrivalMin) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "routeName", alert.getRouteName(),
                    "stopName", alert.getStopName(),
                    "arrivalMin", arrivalMin));
        } catch (Exception e) {
            log.warn("[BusAlertOutbox] payload 직렬화 실패 alert={}: {}", alert.getId(), e.getMessage());
            return null;
        }
    }
}
