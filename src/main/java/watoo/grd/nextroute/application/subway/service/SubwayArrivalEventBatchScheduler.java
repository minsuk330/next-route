package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayArrivalEventBatchScheduler {

    @Value("${batch.arrival-event.enabled:true}")
    private boolean enabled;

    private final SubwayArrivalEventDerivationService derivationService;

    // 매일 04:30 KST
    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void runDailyDerivation() {
        if (!enabled) {
            log.info("[ArrivalEventBatch] Disabled, skipping");
            return;
        }
        LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        log.info("[ArrivalEventBatch] Starting derivation for serviceDate={}", yesterday);
        try {
            int count = derivationService.deriveForDate(yesterday);
            log.info("[ArrivalEventBatch] Completed serviceDate={}, events={}", yesterday, count);
        } catch (Exception e) {
            log.error("[ArrivalEventBatch] Failed for serviceDate={}: {}", yesterday, e.getMessage(), e);
        }
    }
}
