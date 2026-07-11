package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.notifination;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.service.BusAlertOutboxRelayService;

import java.time.Clock;
import java.time.LocalTime;

/**
 * outbox relay 스케줄러. enabled + active-hours 가드 후 relay(=발송).
 * polling(BusArrivalAlertScheduler)과 별개 — polling 은 enqueue 만, 발송은 여기서.
 */
@Component
@RequiredArgsConstructor
public class BusAlertOutboxRelayScheduler {

    private final BusAlertOutboxRelayService relayService;
    private final BusArrivalAlertProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${notification.bus-arrival.outbox-relay-interval-ms:1000}",
            scheduler = "notificationTaskScheduler")
    public void relay() {
        if (!properties.isEnabled()) return;
        if (!isActiveHours()) return;
        relayService.relayOnce();
    }

    /** active window는 자정을 넘을 수 있다(start > end). BusArrivalAlertScheduler 와 동일 규칙. */
    private boolean isActiveHours() {
        LocalTime now = LocalTime.now(clock);
        LocalTime start = LocalTime.parse(properties.getActiveHours().getStart());
        LocalTime end = LocalTime.parse(properties.getActiveHours().getEnd());
        if (start.equals(end)) return true;
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        boolean inactive = !now.isBefore(end) && now.isBefore(start);
        return !inactive;
    }
}
