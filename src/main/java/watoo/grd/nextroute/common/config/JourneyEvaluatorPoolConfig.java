package watoo.grd.nextroute.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 여정 평가 전용 스레드 풀.
 *
 * <p>tick 하나가 여정 수십 건을 평가하고 각 건이 외부 API 를 기다린다. 한 스레드로 순서대로 돌면
 * 느린 provider 하나가 나머지 전부를 밀어낸다.
 *
 * <p><b>큐가 차면 거부한다.</b> {@code CallerRunsPolicy} 를 쓰지 않는 것이 중요하다 — 그것을 쓰면
 * 넘친 작업을 tick 스레드가 직접 처리하게 되고, 그동안 다음 claim 이 멈춘다. 거부된 여정은 선점
 * 시각이 지나면 다시 잡히므로 잃어버리지 않는다.
 *
 * <p>{@code TaskScheduler} 는 새로 만들지 않는다({@link SchedulingConfig} 참고). tick 자체는 공용
 * 스케줄러를 쓰고, 여기서 만드는 것은 <b>작업 실행기</b>다.
 */
@Configuration
@Profile("worker")
@RequiredArgsConstructor
public class JourneyEvaluatorPoolConfig {

    @Bean("journeyEvaluatorExecutor")
    public ThreadPoolTaskExecutor journeyEvaluatorExecutor(JourneyEvaluatorProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getPoolSize());
        executor.setMaxPoolSize(properties.getPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("journey-eval-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 종료 시 진행 중인 평가를 기다리지 않는다. 결과를 못 쓰면 선점이 만료돼 다시 잡힌다.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
