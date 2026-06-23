package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.service.BusAlertDispatchService;

import java.time.Clock;
import java.time.LocalTime;

/**
 * 버스 도착 알림 폴링 스케줄러. enabled + active-hours(자정 넘김) 가드 후 디스패치.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusArrivalAlertScheduler {

    private final BusAlertDispatchService dispatchService;
    private final BusArrivalAlertProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${notification.bus-arrival.poll-interval-ms:30000}")
    public void poll() {
        if (!properties.isEnabled()) return;
        if (!isActiveHours()) return;
        dispatchService.dispatchDue();
    }

    /**
     * active window는 자정을 넘을 수 있다(start > end). 예: 05:00~01:00 → 비활성 구간 [01:00, 05:00).
     */
    private boolean isActiveHours() {
        LocalTime now = LocalTime.now(clock);
        LocalTime start = LocalTime.parse(properties.getActiveHours().getStart());
        LocalTime end = LocalTime.parse(properties.getActiveHours().getEnd());
        if (start.equals(end)) return true;             // 24시간
        if (start.isBefore(end)) {                       // 같은 날 구간
            return !now.isBefore(start) && now.isBefore(end);
        }
        // 자정 넘김: 비활성 = [end, start)
        boolean inactive = !now.isBefore(end) && now.isBefore(start);
        return !inactive;
    }
}
