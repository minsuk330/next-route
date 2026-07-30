package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/** 지하철 도착 이벤트 파생·보강·정답 생성을 날짜 단위로 실행하는 공용 업무 서비스. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayArrivalEventBatchService {

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

    public BatchRunResult runForDate(LocalDate serviceDate) {
        if (!enabled) {
            log.info("[ArrivalEventBatch] Disabled, skipping serviceDate={}", serviceDate);
            return new BatchRunResult(serviceDate, 0, 0, 0, 0);
        }
        log.info("[ArrivalEventBatch] Starting derivation for serviceDate={}", serviceDate);

        int derived;
        try {
            derived = derivationService.deriveForDate(serviceDate);
            log.info("[BatchService] Phase A complete: serviceDate={} events={}", serviceDate, derived);
        } catch (Exception e) {
            log.error("[BatchService] Phase A failed for serviceDate={}, skipping Phase B/C/Truth", serviceDate, e);
            return new BatchRunResult(serviceDate, 0, 0, 0, 0);
        }

        int matched = 0;
        int inferred = 0;
        if (matchIssueEnabled) {
            try {
                matched = matchingService.matchForDate(serviceDate);
                log.info("[BatchService] Phase B-1 complete: serviceDate={} issues={}", serviceDate, matched);

                if (inferredCompletionEnabled) {
                    inferred = inferredCompletionService.completeForDate(serviceDate);
                    log.info("[BatchService] Phase C complete: serviceDate={} inferredEvents={}",
                            serviceDate, inferred);

                    matched = matchingService.matchForDate(serviceDate);
                    log.info("[BatchService] Phase B-2 complete: serviceDate={} issues={}", serviceDate, matched);
                } else {
                    log.info("[BatchService] Phase C disabled (batch.inferred-completion.enabled=false)");
                }
            } catch (Exception e) {
                log.error("[BatchService] Phase B/C failed for serviceDate={}", serviceDate, e);
            }
        } else {
            log.info("[BatchService] Phase B/C disabled (batch.match-issue.enabled=false) — truth uses Phase A event set");
        }

        int delayTruthRows = 0;
        if (delayTruthEnabled) {
            try {
                delayTruthRows = delayTruthService.generateForDate(serviceDate);
                log.info("[BatchService] Phase Truth complete: serviceDate={} truthRows={}",
                        serviceDate, delayTruthRows);
            } catch (Exception e) {
                log.error("[BatchService] Phase Truth failed for serviceDate={}", serviceDate, e);
            }
        } else {
            log.info("[BatchService] Phase Truth disabled (batch.delay-truth.enabled=false)");
        }

        return new BatchRunResult(serviceDate, derived, inferred, matched, delayTruthRows);
    }
}
