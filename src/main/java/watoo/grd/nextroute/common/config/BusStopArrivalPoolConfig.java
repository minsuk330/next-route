package watoo.grd.nextroute.common.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import watoo.grd.nextroute.application.arrival.config.BusStopArrivalProperties;

/**
 * 정류장 단위 도착(#577)의 요청 안 병렬 조회 실행기. 검색·평가기 실행기와 공유하지 않는다 — 근처 화면 폴링이 경로 검색을
 * 밀어내지 않게. 큐가 없어 몰리면 바로 거절되고, 그 정류장은 LIMITED 로 응답한다(기다리게 하지 않는다).
 */
@Configuration
public class BusStopArrivalPoolConfig {

    @Bean("busStopArrivalExecutor")
    public ThreadPoolTaskExecutor busStopArrivalExecutor(BusStopArrivalProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getExecutorCoreSize());
        executor.setMaxPoolSize(Math.max(properties.getExecutorCoreSize(), properties.getExecutorMaxSize()));
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("bus-stop-arrival-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
