package watoo.grd.nextroute.application.route.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import watoo.grd.nextroute.application.route.service.RouteSearchEventRecorder;
import watoo.grd.nextroute.domain.route.log.repository.RouteSearchEventRepository;

import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 경로 검색 기록기(사용 퍼널). 검색을 받는 api 쪽에만 둔다.
 * 끄기: {@code route.search-event.enabled=false}(컨트롤러는 기록 없이 동작).
 */
@Configuration
@Profile("!worker")
@ConditionalOnProperty(name = "route.search-event.enabled", havingValue = "true", matchIfMissing = true)
public class RouteSearchEventConfig {

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor searchEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1_000);
        executor.setThreadNamePrefix("search-event-");
        // 넘치면 예외 → 기록기가 받아 WARN 후 버림. 요청 스레드에서 DB 를 쓰지 않는다(CallerRuns 금지).
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    public RouteSearchEventRecorder routeSearchEventRecorder(RouteSearchEventRepository repository, Clock clock) {
        return new RouteSearchEventRecorder(repository, searchEventExecutor(), clock);
    }
}
