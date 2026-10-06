package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.service.RerouteBoardRefreshClaimService;
import watoo.grd.nextroute.application.journey.service.RerouteBoardRefreshService;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * 재탐색 보드 주기 갱신(#264 PR-2). worker 전용, 보드 플래그가 켜졌을 때만 뜬다.
 *
 * <p>선점 → 전용 실행기에서 갱신 → 마무리(다음 예정 심기). 마무리는 갱신이 실패해도 한다 — 안 하면
 * lease 가 풀릴 때까지 그 여정이 멈춘다. 선점한 만큼만 실행기에 넣으므로 tick 이 기다리지 않는다.
 */
@Slf4j
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "journey.reroute.board", name = "enabled", havingValue = "true")
public class RerouteBoardRefreshScheduler {

    private final RerouteBoardRefreshClaimService claimService;
    private final RerouteBoardRefreshService refreshService;
    private final ThreadPoolTaskExecutor executor;
    private final JourneyRerouteProperties properties;

    public RerouteBoardRefreshScheduler(RerouteBoardRefreshClaimService claimService,
                                        RerouteBoardRefreshService refreshService,
                                        @Qualifier("rerouteBoardRefreshExecutor")
                                        ThreadPoolTaskExecutor executor,
                                        JourneyRerouteProperties properties) {
        this.claimService = claimService;
        this.refreshService = refreshService;
        this.executor = executor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${journey.reroute.board.refresh.tick-ms:1000}")
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            List<RerouteBoardRefreshClaimService.Claim> claims = claimService.claim();
            for (RerouteBoardRefreshClaimService.Claim claim : claims) {
                submit(claim);
            }
        } catch (Exception e) {
            log.error("[RerouteBoard] tick 실패: {}", e.getMessage(), e);
        }
    }

    private void submit(RerouteBoardRefreshClaimService.Claim claim) {
        try {
            executor.execute(() -> refreshOne(claim));
        } catch (RejectedExecutionException e) {
            // 실행기가 찼다. lease 가 풀리면 다시 잡힌다.
            log.warn("[RerouteBoard] 실행기 포화 — journeyId={} 은 다음 선점으로 미룬다", claim.journeyId());
        }
    }

    private void refreshOne(RerouteBoardRefreshClaimService.Claim claim) {
        try {
            refreshService.refresh(claim.journeyId());
        } catch (Exception e) {
            log.warn("[RerouteBoard] 갱신 실패 journeyId={}: {}", claim.journeyId(), e.getMessage());
        } finally {
            try {
                claimService.finish(claim);
            } catch (Exception e) {
                log.warn("[RerouteBoard] 마무리 실패 journeyId={}: {}", claim.journeyId(), e.getMessage());
            }
        }
    }
}
