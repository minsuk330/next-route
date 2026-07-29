package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.notifination;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.service.BusAlertOutboxRelayService;
import watoo.grd.nextroute.application.notification.service.RelayWaker;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class BusAlertRelayLoopTest {

    private BusAlertRelayLoop loop;

    @AfterEach
    void tearDown() {
        if (loop != null) {
            loop.stop();
        }
    }

    @Test
    void timeoutSweepDrainsEvenWithoutWakeSignal() {
        BusAlertOutboxRelayService relayService = mock(BusAlertOutboxRelayService.class);
        BusArrivalAlertProperties properties = new BusArrivalAlertProperties();
        properties.setEnabled(true);
        properties.setOutboxSweepIntervalMs(20);
        properties.getActiveHours().setStart("00:00");
        properties.getActiveHours().setEnd("00:00");

        loop = new BusAlertRelayLoop(
                relayService,
                new RelayWaker(),
                properties,
                Clock.fixed(Instant.parse("2026-07-28T08:00:00Z"), ZoneId.of("Asia/Seoul")));

        loop.start();

        verify(relayService, timeout(1000).atLeastOnce()).drainUntilEmpty();
    }
}
