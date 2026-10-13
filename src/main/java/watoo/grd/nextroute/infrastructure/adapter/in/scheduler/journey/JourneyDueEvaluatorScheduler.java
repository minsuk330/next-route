package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.observability.JourneyEvaluationMetrics;
import watoo.grd.nextroute.application.journey.service.JourneyDueClaimService;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationService;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationService.Report;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 평가할 때가 된 여정을 골라 worker 들에게 나눠 준다.
 *
 * <p>고르는 일과 계산하는 일을 나눈 이유. 한 스레드가 둘을 다 하면 느린 provider 하나 때문에
 * 다음 여정을 고르는 것 자체가 멈춘다. 여기서는 고르기만 하고, 계산은 전용 풀이 병렬로 한다.
 *
 * <p>worker 프로필에서만, 그리고 <b>명시적으로 켰을 때만</b> 돈다. 평가기는 외부 API 를 상시로
 * 부르므로 배포와 동시에 도는 것이 아니라 운영이 고른 시점에 켜야 한다.
 *
 * <p>여러 인스턴스가 동시에 돌아도 된다. 같은 여정을 두 번 평가하지 않는 책임은 스케줄러가 아니라
 * {@code claimDueBatch} 의 {@code SKIP LOCKED} 와 선점 시각이 진다.
 */
@Slf4j
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "journey.navigation.evaluator", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
public class JourneyDueEvaluatorScheduler {

    private final JourneyDueClaimService claimService;
    private final JourneyEvaluationService evaluationService;
    private final JourneyEvaluationMetrics metrics;
    private final JourneyEvaluatorProperties properties;

    @Qualifier("journeyEvaluatorExecutor")
    private final ThreadPoolTaskExecutor executor;

    @Scheduled(fixedDelayString = "${journey.navigation.evaluator.tick-ms:5000}")
    public void run() {
        try {
            tick();
        } catch (Exception e) {
            // tick 하나가 죽어도 다음 tick 은 살아 있어야 한다.
            log.error("[JourneyDueEvaluator] tick 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * 한 tick — 선점하고, 병렬로 평가하고, 다 끝나면 돌아온다.
     *
     * <p>배치가 끝날 때까지 기다리는 이유는 밀림(backlog)을 관측할 수 있게 하려는 것이다. 기다리지
     * 않고 계속 선점하면 큐만 길어지고 어디가 느린지 보이지 않는다.
     *
     * <p>상한을 넘겨 못 끝낸 작업은 버려도 된다. 선점 시각이 지나면 다른 tick 이 그 여정을 다시
     * 잡고, 늦게 끝난 워커의 결과는 소유권 확인에서 걸러진다.
     */
    private void tick() {
        if (properties.getClaimOrder() == JourneyEvaluatorProperties.ClaimOrder.URGENT_FIRST) {
            refill();
            return;
        }
        List<JourneyDueClaim> claims = claimService.claimDue();
        metrics.recordClaimed(claims.size());
        if (claims.isEmpty()) {
            return;
        }

        List<CompletableFuture<Void>> running = claims.stream()
                .map(this::submit)
                .filter(java.util.Objects::nonNull)
                .toList();

        try {
            CompletableFuture.allOf(running.toArray(CompletableFuture[]::new))
                    .get(properties.getBatchDeadlineMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[JourneyDueEvaluator] 배치가 {}ms 안에 끝나지 않았다. 남은 여정은 선점 만료 후 다시 잡힌다",
                    properties.getBatchDeadlineMs());
        }
    }

    /**
     * 여정 하나를 worker 에 맡긴다.
     *
     * @return 큐가 차서 맡기지 못했으면 null
     */
    /**
     * 워커가 곧바로 집을 수 있는 만큼만 선점하고, 끝나기를 기다리지 않는다.
     *
     * <p>배치(200건)를 한꺼번에 잡으면 처리량(초당 수 건)을 훨씬 넘는 여정이 작업 큐에 들어가 들어온
     * 순서대로 기다린다. 그러면 선점 쿼리가 급한 환승을 먼저 골라도 실제 처리 순서는 큐가 정한다
     * (측정: 급한 여정 판단 나이 p95 54.7 → 54.0초, 효과 없음). 그렇다고 작게 잡고 묶음이 끝나기를
     * 기다리면 tick 사이에 워커가 논다(측정: 처리량 8.6 → 5.0회/s).
     *
     * <p>그래서 빈자리만큼만 잡아 넘기고 바로 돌아온다. 다음 tick 이 다시 빈자리를 채운다. 큐에는 워커
     * 수만큼만 여분을 둬 tick 사이에도 워커가 쉬지 않게 한다. 큐가 짧으니 잡은 여정이 선점 만료 전에
     * 시작된다.
     */
    private void refill() {
        int busy = executor.getActiveCount() + executor.getThreadPoolExecutor().getQueue().size();
        int free = properties.getPoolSize() * 2 - busy;
        if (free <= 0) {
            return;
        }
        List<JourneyDueClaim> claims = claimService.claimDue(free);
        metrics.recordClaimed(claims.size());
        claims.forEach(this::submit);
    }

    private CompletableFuture<Void> submit(JourneyDueClaim claim) {
        try {
            return CompletableFuture.runAsync(() -> evaluateOne(claim), executor);
        } catch (RejectedExecutionException e) {
            // 큐가 찼다. 여기서 직접 처리하면 다음 선점이 멈추므로 포기한다. 선점 시각이 지나면
            // 다시 잡힌다.
            metrics.recordFailure("queue_full");
            return null;
        }
    }

    private void evaluateOne(JourneyDueClaim claim) {
        long startedAt = System.nanoTime();
        try {
            Report report = evaluationService.evaluate(claim);
            metrics.recordEvaluation(report.result().name().toLowerCase(),
                    System.nanoTime() - startedAt);
            metrics.recordPredictions(report.assessments());
            if (report.result() == JourneyEvaluationService.Result.LOST_CLAIM) {
                metrics.recordLostClaim();
            }
            if (report.upstreamBlocked()) {
                // 실패로 세지 않는다. 판정은 남았고(모름), 다음 평가만 늦춰 심었다.
                metrics.recordFailure("provider_blocked");
            }
        } catch (DataAccessException e) {
            metrics.recordFailure("storage");
            metrics.recordEvaluation("failed", System.nanoTime() - startedAt);
            log.warn("[JourneyDueEvaluator] 저장 실패 — journeyId={}: {}",
                    claim.journeyId(), e.getMessage());
        } catch (Exception e) {
            // 여정 하나의 실패는 그 여정만 격리한다. 선점 시각이 지나면 다시 잡힌다.
            metrics.recordFailure("transient");
            metrics.recordEvaluation("failed", System.nanoTime() - startedAt);
            log.warn("[JourneyDueEvaluator] 평가 실패 — journeyId={}: {}",
                    claim.journeyId(), e.getMessage(), e);
        }
    }
}
