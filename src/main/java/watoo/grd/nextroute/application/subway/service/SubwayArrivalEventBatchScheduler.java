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

    @Value("${batch.delay-truth.enabled:true}")
    private boolean delayTruthEnabled;

    private final SubwayArrivalEventDerivationService derivationService;
    private final TimetableMatchingService matchingService;
    private final SubwayInferredArrivalCompletionService inferredCompletionService;
    private final SubwayDelayTruthGenerationService delayTruthService;

    public record BatchRunResult(LocalDate serviceDate, int derivedEvents,
                                 int inferredEvents, int matchIssues,
                                 int delayTruthRows) {}

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void runDailyDerivation() {
        runForDate(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1));
    }

    public BatchRunResult runForDate(LocalDate serviceDate) {
        if (!enabled) {
            log.info("[ArrivalEventBatch] Disabled, skipping serviceDate={}", serviceDate);
            return new BatchRunResult(serviceDate, 0, 0, 0, 0);
        }
        log.info("[ArrivalEventBatch] Starting derivation for serviceDate={}", serviceDate);

        // Phase A — derive
        int derived;
        try {
            derived = derivationService.deriveForDate(serviceDate);
            log.info("[BatchScheduler] Phase A complete: serviceDate={} events={}", serviceDate, derived);
        } catch (Exception e) {
            log.error("[BatchScheduler] Phase A failed for serviceDate={}, skipping Phase B/C/Truth", serviceDate, e);
            return new BatchRunResult(serviceDate, 0, 0, 0, 0);
        }

        // Phase B/C — issue 진단 + event set 확정 (matchIssueEnabled 종속)
        // Phase C는 B-1의 NO_RAW_EVENT issue를 입력으로 쓰므로 구조적으로
        // matchIssueEnabled 와 묶여 있다. matchIssueEnabled=false면 event set은
        // Phase A 한정(Phase C 미적용)으로 truth가 만들어진다.
        int matched = 0;
        int inferred = 0;
        if (matchIssueEnabled) {
            try {
                matched = matchingService.matchForDate(serviceDate); // B-1
                log.info("[BatchScheduler] Phase B-1 complete: serviceDate={} issues={}", serviceDate, matched);

                if (inferredCompletionEnabled) {
                    inferred = inferredCompletionService.completeForDate(serviceDate); // Phase C
                    log.info("[BatchScheduler] Phase C complete: serviceDate={} inferredEvents={}",
                            serviceDate, inferred);

                    matched = matchingService.matchForDate(serviceDate); // B-2 (최종 issue)
                    log.info("[BatchScheduler] Phase B-2 complete: serviceDate={} issues={}", serviceDate, matched);
                } else {
                    log.info("[BatchScheduler] Phase C disabled (batch.inferred-completion.enabled=false)");
                }
            } catch (Exception e) {
                log.error("[BatchScheduler] Phase B/C failed for serviceDate={}", serviceDate, e);
            }
        } else {
            log.info("[BatchScheduler] Phase B/C disabled (batch.match-issue.enabled=false) — truth uses Phase A event set");
        }

        // Truth — issue 진단과 *독립*, 항상 최종 event set 확정 이후 실행
        // 자체 try/catch 로 앞 단계 결과를 마스킹하지 않는다.
        int delayTruthRows = 0;
        if (delayTruthEnabled) {
            try {
                delayTruthRows = delayTruthService.generateForDate(serviceDate);
                log.info("[BatchScheduler] Phase Truth complete: serviceDate={} truthRows={}",
                        serviceDate, delayTruthRows);
            } catch (Exception e) {
                log.error("[BatchScheduler] Phase Truth failed for serviceDate={}", serviceDate, e);
            }
        } else {
            log.info("[BatchScheduler] Phase Truth disabled (batch.delay-truth.enabled=false)");
        }

        return new BatchRunResult(serviceDate, derived, inferred, matched, delayTruthRows);
    }
}
