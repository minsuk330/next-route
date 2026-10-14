package watoo.grd.nextroute.common.config;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties;
import org.springframework.boot.actuate.autoconfigure.metrics.PropertiesMeterFilter;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조회 응답·커넥션 대기 지표가 p95 를 구할 구간을 내보내는지.
 *
 * <p>설정 키가 빠지거나 오타가 나면 조용히 무시되고 지표는 횟수·합계만 남는다. 운영에서 p95 가 안
 * 나오는 걸 보고서야 알게 되므로, 실제 application.yaml 을 Boot 와 같은 필터로 걸어 확인한다.
 */
@DisplayName("지표 구간 설정")
class MetricsDistributionConfigTest {

    @Test
    @DisplayName("조회 응답 시간은 50ms~5s 경계 구간을 내보낸다")
    void httpServerRequestsHasSloBuckets() throws IOException {
        assertThat(bucketsOf("http.server.requests", TimeUnit.MILLISECONDS))
                .contains(50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0);
    }

    @Test
    @DisplayName("커넥션 풀 대기는 1ms~1s 경계 구간을 내보낸다")
    void hikariAcquireHasSloBuckets() throws IOException {
        assertThat(bucketsOf("hikaricp.connections.acquire", TimeUnit.MILLISECONDS))
                .contains(1.0, 5.0, 10.0, 50.0, 100.0, 500.0, 1000.0);
    }

    private static List<Double> bucketsOf(String name, TimeUnit unit) throws IOException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new PropertiesMeterFilter(metricsProperties()));
        Timer timer = Timer.builder(name).register(registry);
        timer.record(Duration.ofMillis(3));
        CountAtBucket[] buckets = timer.takeSnapshot().histogramCounts();
        return Arrays.stream(buckets).map(b -> b.bucket(unit)).toList();
    }

    private static MetricsProperties metricsProperties() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment)
                .bind("management.metrics", MetricsProperties.class)
                .orElseGet(MetricsProperties::new);
    }
}
