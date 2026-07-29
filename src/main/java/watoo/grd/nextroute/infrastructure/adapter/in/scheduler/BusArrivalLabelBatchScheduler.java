package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.service.BusArrivalLabelBatchService;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
@Profile("worker")
@RequiredArgsConstructor
public class BusArrivalLabelBatchScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final BusArrivalLabelBatchService batchService;

    @Scheduled(cron = "${batch.bus-label.cron:0 50 4 * * *}", zone = "Asia/Seoul")
    public void runDailyLabelGeneration() {
        batchService.runForDate(LocalDate.now(KST).minusDays(1));
    }
}
