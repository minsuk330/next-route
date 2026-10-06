package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.service.RerouteBoardRefreshClaimService;
import watoo.grd.nextroute.application.journey.service.RerouteBoardRefreshService;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("재탐색 보드 갱신 스케줄러")
class RerouteBoardRefreshSchedulerTest {

    private final RerouteBoardRefreshClaimService claimService = mock(RerouteBoardRefreshClaimService.class);
    private final RerouteBoardRefreshService refreshService = mock(RerouteBoardRefreshService.class);
    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    private final JourneyRerouteProperties properties = new JourneyRerouteProperties();
    private final RerouteBoardRefreshClaimService.Claim claim =
            new RerouteBoardRefreshClaimService.Claim(7L, Instant.parse("2026-10-07T09:01:00Z"));

    {
        properties.setEnabled(true);
        // 실행기에 넣은 작업을 그 자리에서 돌린다.
        doAnswer(call -> {
            ((Runnable) call.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("갱신이 터져도 마무리(다음 예정 심기)는 한다 — 안 하면 lease 가 풀릴 때까지 멈춘다")
    void finishesEvenWhenRefreshFails() {
        given(claimService.claim()).willReturn(List.of(claim));
        given(refreshService.refresh(7L)).willThrow(new IllegalStateException("boom"));

        new RerouteBoardRefreshScheduler(claimService, refreshService, executor, properties).run();

        verify(claimService).finish(claim);
    }

    @Test
    @DisplayName("기능이 꺼져 있으면 선점하지 않는다")
    void disabledFeatureDoesNotClaim() {
        properties.setEnabled(false);

        new RerouteBoardRefreshScheduler(claimService, refreshService, executor, properties).run();

        verify(claimService, never()).claim();
    }
}
