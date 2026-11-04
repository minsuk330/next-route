package watoo.grd.nextroute.application.user.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import watoo.grd.nextroute.application.user.service.DailyActivityRecorder;
import watoo.grd.nextroute.domain.user.repository.UserDailyActivityRepository;

import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 사용 통계(DAU) 기록기. 앱 요청을 받는 api 쪽에만 둔다 — worker 는 사용자 요청을 받지 않는다.
 * 끄기: {@code usage.daily-activity.enabled=false}(필터도 붙지 않는다).
 */
@Configuration
@Profile("!worker")
@ConditionalOnProperty(name = "usage.daily-activity.enabled", havingValue = "true", matchIfMissing = true)
public class DailyActivityConfig {

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor dailyActivityExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1_000);
        executor.setThreadNamePrefix("daily-activity-");
        // 넘치면 예외 → 기록기가 받아 WARN 후 다음 요청 때 재시도. 요청 스레드에서 돌리지 않는다.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    public DailyActivityRecorder dailyActivityRecorder(UserDailyActivityRepository repository, Clock clock) {
        return new DailyActivityRecorder(repository, dailyActivityExecutor(), clock);
    }
}
