package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.notifination;

import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.service.BusAlertDispatchService;
import watoo.grd.nextroute.common.lock.FixedLeaderLease;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class BusArrivalAlertSchedulerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-23T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    private BusArrivalAlertProperties allDay() {
        BusArrivalAlertProperties props = new BusArrivalAlertProperties();
        props.getActiveHours().setStart("00:00");
        props.getActiveHours().setEnd("00:00");
        return props;
    }

    @Test
    void TC_리더가_아니면_디스패치를_부르지_않는다() {
        BusAlertDispatchService dispatch = mock(BusAlertDispatchService.class);
        BusArrivalAlertScheduler scheduler =
                new BusArrivalAlertScheduler(dispatch, allDay(), clock, FixedLeaderLease.notLeader());

        scheduler.poll();

        verify(dispatch, never()).dispatchDue();
    }

    @Test
    void TC_리더면_디스패치를_부른다() {
        BusAlertDispatchService dispatch = mock(BusAlertDispatchService.class);
        BusArrivalAlertScheduler scheduler =
                new BusArrivalAlertScheduler(dispatch, allDay(), clock, FixedLeaderLease.leader());

        scheduler.poll();

        verify(dispatch).dispatchDue();
    }
}
