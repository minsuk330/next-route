package watoo.grd.nextroute.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 보드 갱신 전용 실행기(#264 PR-2). 평가기 실행기·provider 슬롯과 <b>공유하지 않는다</b> — 갱신 한 회차가
 * 경로 검색·실시간·예측으로 몇 초씩 잡으므로, 섞으면 환승 위험 판정이 밀린다.
 */
@Configuration
@Profile("worker")
@ConditionalOnProperty(prefix = "journey.reroute.board", name = "enabled", havingValue = "true")
public class RerouteBoardRefreshPoolConfig {

    @Bean("rerouteBoardRefreshExecutor")
    public ThreadPoolTaskExecutor rerouteBoardRefreshExecutor(JourneyRerouteProperties properties) {
        JourneyRerouteProperties.Board.Refresh refresh = properties.getBoard().getRefresh();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(refresh.getPermits());
        executor.setMaxPoolSize(refresh.getPermits());
        // 선점한 만큼만 받는다. 넘치면 거절되고 그 여정은 lease 가 풀린 뒤 다시 잡힌다.
        executor.setQueueCapacity(refresh.getBatchSize());
        executor.setThreadNamePrefix("reroute-board-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
