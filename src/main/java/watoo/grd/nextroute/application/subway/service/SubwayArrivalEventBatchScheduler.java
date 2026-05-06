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

    @Value("${batch.match-issue.enabled:true}")
    private boolean matchIssueEnabled;

    private final SubwayArrivalEventDerivationService derivationService;
    private final TimetableMatchingService matchingService;

    // 매일 04:30 KST
    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void runDailyDerivation() {
        if (!enabled) {
            log.info("[ArrivalEventBatch] Disabled, skipping");
            return;
        }
        LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        log.info("[ArrivalEventBatch] Starting derivation for serviceDate={}", yesterday);

        // Phase A: Arrival Event Derivation
        try {
            int count = derivationService.deriveForDate(yesterday);
            log.info("[BatchScheduler] Phase A complete: serviceDate={} events={}", yesterday, count);
        } catch (Exception e) {
            log.error("[BatchScheduler] Phase A failed for serviceDate={}, skipping Phase B", yesterday, e);
            return;
        }

        // Phase B: Timetable Matching
        if (!matchIssueEnabled) {
            log.info("[BatchScheduler] Phase B disabled (batch.match-issue.enabled=false)");
            return;
        }

        try {
            int issueCount = matchingService.matchForDate(yesterday);
            log.info("[BatchScheduler] Phase B complete: serviceDate={} issues={}", yesterday, issueCount);
        } catch (Exception e) {
            log.error("[BatchScheduler] Phase B failed for serviceDate={}", yesterday, e);
        }
    }
}
