package watoo.grd.nextroute.application.journey.config;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 재탐색 후보 overlay 전용 실행 풀(bulkhead).
 *
 * <p>후보 하나의 overlay 는 <b>대기 수만큼</b> 승차 예측을 부르고, 그 응답을 주행 중인 사용자가
 * 기다린다. 경로끼리 나란히 돌려야 실제로 쓸 만한 대기 시간이 나오지만, 그 병렬성이 예측 서버로
 * 그대로 번지면 안 되므로 자리를 좁게 잡는다.
 *
 * <p>포화 시에는 <b>부르는 스레드에서 직접 돈다</b>({@code CallerRunsPolicy} 를 쓰지 않고 소비자가
 * 거절을 받아 스스로 처리한다 — {@code RemainingPathCandidateProvider}). 후보를 잃느니 그 요청만
 * 조금 느린 편이 낫고, 전체 대기는 평가 예산이 여전히 자른다.
 */
@Slf4j
@Configuration
public class RerouteOverlayExecutorConfig {

    @Getter
    @Setter
    @Component
    @ConfigurationProperties(prefix = "journey.reroute.overlay-pool")
    public static class Props {
        /**
         * 동시에 진행할 overlay 수. 후보 상한({@code remaining-path.max-paths})과 같은 크기면
         * 한 요청의 후보가 서로를 기다리지 않는다.
         */
        private int size = 4;
        /** 대기열. 짧게 둔다 — 긴 큐는 지연을 뒤로 미룰 뿐이고, 여기서는 예산이 먼저 끝난다. */
        private int queue = 8;
    }

    @Bean(name = "rerouteOverlayExecutor", destroyMethod = "shutdownNow")
    public ExecutorService rerouteOverlayExecutor(Props props) {
        AtomicInteger seq = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                props.getSize(), props.getSize(),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(props.getQueue()),
                runnable -> {
                    Thread thread = new Thread(runnable, "reroute-overlay-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        log.info("[Bulkhead] 재탐색 overlay 풀 size={} queue={}", props.getSize(), props.getQueue());
        return executor;
    }
}
