package watoo.grd.nextroute.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 알림(도착 알림) 스케줄러 전용 스레드 풀 격리(Step D).
 *
 * <p>공용 스케줄러 풀(spring.task.scheduling.pool.size)은 수집/배치/로테이션 등 16개 @Scheduled 잡이
 * 공유한다. 느린 잡이 풀을 점유하면 알림 폴링/relay 가 밀려(head-of-line blocking) 도착 알림이 지연된다.
 * 알림 잡만 별도 풀(noti-sched-)로 격리해 공용 풀 포화와 무관하게 정시 실행되게 한다.
 *
 * <p>주의: Boot 의 {@code TaskSchedulingAutoConfiguration} 은 {@code @ConditionalOnMissingBean(TaskScheduler)}
 * 라서, TaskScheduler 빈을 하나라도 직접 등록하면 기본 {@code taskScheduler} 자동설정이 사라진다.
 * 그러면 격리는커녕 모든 잡이 한 풀로 몰린다. 이를 막기 위해 공용 풀도 여기서 명시적으로({@code @Primary})
 * 재정의한다 — annotation 없는 @Scheduled 는 기본(taskScheduler)으로 계속 라우팅된다.
 */
@Configuration
public class NotificationSchedulerConfig {

    /** 공용 스케줄러 풀(비-알림 잡). Boot 기본 빈을 대체 — @Primary 로 무-annotation @Scheduled 의 기본 스케줄러. */
    @Bean
    @Primary
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${spring.task.scheduling.pool.size:3}") int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        return scheduler;
    }

    /** 알림 전용 풀. @Scheduled(scheduler = "notificationTaskScheduler") 로 라우팅. 공용 증설이 아니라 격리. */
    @Bean("notificationTaskScheduler")
    public ThreadPoolTaskScheduler notificationTaskScheduler(
            @Value("${notification.scheduler.pool-size:2}") int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("noti-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        return scheduler;
    }
}
