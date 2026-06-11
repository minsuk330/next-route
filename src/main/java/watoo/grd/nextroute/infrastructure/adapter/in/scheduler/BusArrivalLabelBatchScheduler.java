package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.service.BusArrivalLabelGenerationService;

import java.time.LocalDate;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class BusArrivalLabelBatchScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Value("${batch.bus-label.enabled:true}")
    private boolean enabled;

    private final BusArrivalLabelGenerationService labelGenerationService;

    @Scheduled(cron = "${batch.bus-label.cron:0 50 4 * * *}", zone = "Asia/Seoul")
    public void runDailyLabelGeneration() {
        runForDate(LocalDate.now(KST).minusDays(1));
    }

    public int runForDate(LocalDate serviceDate) {
        if (!enabled) {
            log.info("[BusLabelBatch] Disabled, skipping serviceDate={}", serviceDate);
            return 0;
        }
        log.info("[BusLabelBatch] Starting for serviceDate={}", serviceDate);
        long start = System.currentTimeMillis();
        try {
            int count = labelGenerationService.generateForDate(serviceDate);
            log.info("[BusLabelBatch] Completed serviceDate={} rows={} elapsed={}ms",
                    serviceDate, count, System.currentTimeMillis() - start);
            return count;
        } catch (Exception e) {
            log.error("[BusLabelBatch] Failed for serviceDate={}", serviceDate, e);
            return 0;
        }
    }
}
