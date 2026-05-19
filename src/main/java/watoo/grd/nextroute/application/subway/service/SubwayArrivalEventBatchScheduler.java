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

    @Value("${batch.inferred-completion.enabled:true}")
    private boolean inferredCompletionEnabled;

    private final SubwayArrivalEventDerivationService derivationService;
    private final TimetableMatchingService matchingService;
    private final SubwayInferredArrivalCompletionService inferredCompletionService;

    public record BatchRunResult(LocalDate serviceDate, int derivedEvents,
                                 int inferredEvents, int matchIssues) {}

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void runDailyDerivation() {
        runForDate(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1));
    }

    public BatchRunResult runForDate(LocalDate serviceDate) {
        if (!enabled) {
            log.info("[ArrivalEventBatch] Disabled, skipping serviceDate={}", serviceDate);
            return new BatchRunResult(serviceDate, 0, 0, 0);
        }
        log.info("[ArrivalEventBatch] Starting derivation for serviceDate={}", serviceDate);

        // Phase A
        int derived;
        try {
            derived = derivationService.deriveForDate(serviceDate);
            log.info("[BatchScheduler] Phase A complete: serviceDate={} events={}", serviceDate, derived);
        } catch (Exception e) {
            log.error("[BatchScheduler] Phase A failed for serviceDate={}, skipping Phase B/C", serviceDate, e);
            return new BatchRunResult(serviceDate, 0, 0, 0);
        }

        // Phase B
        if (!matchIssueEnabled) {
            log.info("[BatchScheduler] Phase B disabled (batch.match-issue.enabled=false)");
            return new BatchRunResult(serviceDate, derived, 0, 0);
        }

        int matched = 0;
        int inferred = 0;
        try {
            // Phase B-1 (baseline) — TimetableMatchingService가 NO/EXTRA count 자체 로깅 (D5)
            matched = matchingService.matchForDate(serviceDate);
            log.info("[BatchScheduler] Phase B-1 complete: serviceDate={} issues={}", serviceDate, matched);

            if (inferredCompletionEnabled) {
                // Phase C — code=3로 NO_RAW_EVENT 보완
                inferred = inferredCompletionService.completeForDate(serviceDate);
                log.info("[BatchScheduler] Phase C complete: serviceDate={} inferredEvents={}",
                        serviceDate, inferred);

                // Phase B-2 (final) — issue table은 최종 NO/EXTRA만 남음
                matched = matchingService.matchForDate(serviceDate);
                log.info("[BatchScheduler] Phase B-2 complete: serviceDate={} issues={}", serviceDate, matched);
            } else {
                log.info("[BatchScheduler] Phase C disabled (batch.inferred-completion.enabled=false)");
            }
        } catch (Exception e) {
            log.error("[BatchScheduler] Phase B/C failed for serviceDate={}", serviceDate, e);
        }

        return new BatchRunResult(serviceDate, derived, inferred, matched);
    }
}
