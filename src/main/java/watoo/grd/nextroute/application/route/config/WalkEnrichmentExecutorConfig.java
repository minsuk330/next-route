package watoo.grd.nextroute.application.route.config;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TMAP 보행 보강 전용 실행 풀(bulkhead).
 *
 * <p>기본값은 baseline·부하 실험에서 정한다. 상한을 임의로 키우지 않는 이유:
 * 스레드를 늘려도 TMAP 이 빨라지지 않고, provider rate limit 과 VPS CPU 만 더 압박한다.
 * 포화 시에는 대기열을 짧게 두고 거절해 <b>과부하를 숨기지 않는다</b>.
 */
@Slf4j
@Configuration
public class WalkEnrichmentExecutorConfig {

    @Getter
    @Setter
    @Component
    @ConfigurationProperties(prefix = "route.search.tmap-pool")
    public static class Props {
        /** 동시에 진행할 수 있는 TMAP 호출 수. */
        private int size = 8;
        /** 대기열 길이. 짧게 둔다 — 큰 큐는 지연을 뒤로 미룰 뿐이다. */
        private int queue = 16;
    }

    @Bean(name = "walkEnrichmentExecutor", destroyMethod = "shutdownNow")
    public ExecutorService walkEnrichmentExecutor(Props props, RouteSearchMetrics metrics) {
        AtomicInteger seq = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                props.getSize(), props.getSize(),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(props.getQueue()),
                r -> {
                    Thread t = new Thread(r, "tmap-walk-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                // 포화 시 호출 스레드에서 실행(CallerRuns)하지 않는다 — 그러면 요청 스레드가 TMAP 지연을 물게 되고
                // deadline 을 지킬 수 없다. 거절해서 보강만 생략한다.
                new ThreadPoolExecutor.AbortPolicy());
        metrics.registerBulkheadGauges("tmap", executor);
        log.info("[Bulkhead] TMAP 전용 풀 size={} queue={}", props.getSize(), props.getQueue());
        return executor;
    }
}
