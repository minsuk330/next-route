package watoo.grd.nextroute.infrastructure.adapter.out.cache.subway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.application.subway.port.out.SubwayArrivalDepartureLogPort.Row;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 도착 API 출발 기록의 Redis 계약 — 같은 열차는 한 줄, 시간 범위 조회, TTL, 위치 출발 키와 분리.
 *
 * <p>DB 가 필요 없는 계약이라 스프링 컨텍스트 없이 어댑터만 Redis 컨테이너에 붙인다.
 */
@DisplayName("도착 API 출발 기록 Redis 계약")
class RedisSubwayArrivalDepartureLogAdapterIT {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static RedisTemplate<String, String> template;
    private static RedisSubwayArrivalDepartureLogAdapter adapter;

    @BeforeAll
    static void start() {
        REDIS.start();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(),
                REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        adapter = new RedisSubwayArrivalDepartureLogAdapter(template, new ObjectMapper());
    }

    @AfterAll
    static void stop() {
        factory.destroy();
        REDIS.stop();
    }

    @Test
    @DisplayName("같은 열차를 다시 쓰면 바꿔 쓰고, 범위 안만 돌려주며, TTL 이 걸린다")
    void replacesSameTrainAndRangesByTime() {
        LocalDate date = LocalDate.of(2026, 10, 7);
        Instant t0 = Instant.parse("2026-10-07T13:00:00Z");
        adapter.append(date, "1075", "상행", "1075000210", new Row("K1", "청량리", "일반", t0));
        adapter.append(date, "1075", "상행", "1075000210", new Row("K1", "청량리", "일반", t0.plusSeconds(20)));
        adapter.append(date, "1075", "상행", "1075000210", new Row("K2", "왕십리", "급행", t0.plusSeconds(300)));

        assertThat(adapter.find(date, "1075", "상행", "1075000210", t0, t0.plusSeconds(60)))
                .containsExactly(new Row("K1", "청량리", "일반", t0.plusSeconds(20)));
        assertThat(adapter.find(date, "1075", "상행", "1075000210", t0, t0.plusSeconds(600))).hasSize(2);
        assertThat(adapter.find(date, "1075", "하행", "1075000210", t0, t0.plusSeconds(600))).isEmpty();

        String key = RedisSubwayArrivalDepartureLogAdapter.key(date, "1075", "상행", "1075000210");
        assertThat(key).startsWith("subway:adep:"); // 위치 출발(subway:dep:) 과 분리
        assertThat(template.getExpire(key)).isPositive();
    }
}
