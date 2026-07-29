package watoo.grd.nextroute.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 앱 전역 @Scheduled 스케줄러 풀 구성. 공용 풀 + 알림 전용 풀을 모두 소유한다.
 *
 * <p>동기(Step D 알림 격리): 공용 스케줄러 풀(spring.task.scheduling.pool.size)은 수집/배치/로테이션 등
 * 다수 @Scheduled 잡이 공유한다. 느린 잡이 풀을 점유하면 알림 폴링/relay 가 밀려(head-of-line blocking)
 * 도착 알림이 지연된다. 알림 잡만 별도 풀(noti-sched-)로 격리해 공용 포화와 무관하게 정시 실행되게 한다.
 *
 * <p>주의: Boot 의 {@code TaskSchedulingAutoConfiguration} 은 {@code @ConditionalOnMissingBean(TaskScheduler)}
 * 라서, TaskScheduler 빈을 하나라도 직접 등록하면 기본 {@code taskScheduler} 자동설정이 사라진다.
 * 그러면 격리는커녕 모든 잡이 한 풀로 몰린다. 이를 막기 위해 공용 풀도 여기서 명시적으로({@code @Primary})
 * 재정의한다 — annotation 없는 @Scheduled 는 기본(taskScheduler)으로 계속 라우팅된다.
 */
@Configuration
public class SchedulingConfig {

    /**
     * 공용 스케줄러 풀(비-알림 잡). Boot 기본 빈을 대체 — @Primary 로 무-annotation @Scheduled 의 기본 스케줄러.
     * <p>Boot 기본과 동일 시맨틱(pool size/prefix 유지, shutdown 시 진행 잡 대기 안 함)으로 두어
     * 비-알림 잡의 종료 행동을 바꾸지 않는다. graceful 대기는 알림 풀에만 적용한다.
     */
    @Bean
    @Primary
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${spring.task.scheduling.pool.size:3}") int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("scheduling-");
        return scheduler;
    }

    /**
     * 알림 전용 풀. @Scheduled(scheduler = "notificationTaskScheduler") 로 라우팅. 공용 증설이 아니라 격리.
     * <p>종료 시 진행 중 relay 발송 유실을 막기 위해 graceful shutdown(최대 20s 대기)을 켠다.
     */
    @Bean("notificationTaskScheduler")
    @Profile("worker")
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
