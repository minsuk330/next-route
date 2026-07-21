package watoo.grd.nextroute.application.route.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 검색 이력 저장 전용 bounded 실행 풀. 저장을 응답 경로에서 완전히 분리한다.
 *
 * <p>포화 시 {@link ThreadPoolExecutor.AbortPolicy} 로 거절한다 — CallerRuns 를 쓰면 요청 스레드가
 * DB 쓰기를 물게 돼 응답이 느려지므로 쓰지 않는다. 거절은 호출부(recorder)에서 잡아 drop metric 으로 남긴다.
 */
@Slf4j
@Configuration
public class SearchHistoryExecutorConfig {

    @Bean(name = "searchHistoryExecutor")
    public Executor searchHistoryExecutor(RouteSearchHistoryProperties props) {
        RouteSearchHistoryProperties.Executor cfg = props.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(cfg.getCoreSize());
        executor.setMaxPoolSize(cfg.getMaxSize());
        executor.setQueueCapacity(cfg.getQueueCapacity());
        executor.setThreadNamePrefix("search-history-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        log.info("[SearchHistory] 저장 전용 풀 core={} max={} queue={}",
                cfg.getCoreSize(), cfg.getMaxSize(), cfg.getQueueCapacity());
        return executor;
    }
}
