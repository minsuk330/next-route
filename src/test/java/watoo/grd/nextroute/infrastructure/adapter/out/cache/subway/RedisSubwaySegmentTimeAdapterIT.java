package watoo.grd.nextroute.infrastructure.adapter.out.cache.subway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구간 실측 표본의 Redis 계약 — 첫 출발만 기록, 도착 시 표본 추가, 3개 유지, 범위 밖 버림, TTL.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("구간 실측 소요시간 캐시 계약")
class RedisSubwaySegmentTimeAdapterIT {

    private static final String LINE = "1002";
    private static final String DIR = "내선";
    private static final String ST = "1002000234";
    private static final Instant T0 = Instant.parse("2026-10-07T13:00:00Z");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired RedisSubwaySegmentTimeAdapter adapter;
    @Autowired RedisTemplate<String, String> redisTemplate;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void clear() {
        redisTemplate.delete(RedisSubwaySegmentTimeAdapter.depKey(LINE, DIR, ST));
        redisTemplate.delete(RedisSubwaySegmentTimeAdapter.durKey(LINE, DIR, ST));
    }

    @Test
    @DisplayName("출발은 처음 본 시각만 남고, 도착하면 소요시간 표본이 생기며 출발 기록은 지워진다")
    void firstDepartureThenArrivalMakesSample() {
        adapter.recordDeparted(LINE, DIR, ST, "7462", T0);
        adapter.recordDeparted(LINE, DIR, ST, "7462", T0.plusSeconds(16));
        assertThat(adapter.departedAt(LINE, DIR, ST, "7462")).contains(T0);
        assertThat(redisTemplate.getExpire(RedisSubwaySegmentTimeAdapter.depKey(LINE, DIR, ST))).isPositive();

        adapter.recordArrived(LINE, DIR, ST, "7462", T0.plusSeconds(48));

        assertThat(adapter.recentSeconds(LINE, DIR, ST)).containsExactly(48L);
        assertThat(adapter.departedAt(LINE, DIR, ST, "7462")).isEmpty();
        assertThat(redisTemplate.getExpire(RedisSubwaySegmentTimeAdapter.durKey(LINE, DIR, ST))).isPositive();
    }

    @Test
    @DisplayName("표본은 최신순 3개만 남는다. 도착이 반복 관측돼도 한 번만 센다")
    void keepsLatestThree() {
        long[] seconds = {40, 50, 60, 70};
        for (int i = 0; i < seconds.length; i++) {
            String train = "t" + i;
            Instant dep = T0.plusSeconds(300L * i);
            adapter.recordDeparted(LINE, DIR, ST, train, dep);
            adapter.recordArrived(LINE, DIR, ST, train, dep.plusSeconds(seconds[i]));
            adapter.recordArrived(LINE, DIR, ST, train, dep.plusSeconds(seconds[i] + 16));
        }

        assertThat(adapter.recentSeconds(LINE, DIR, ST)).containsExactly(70L, 60L, 50L);
    }

    @Test
    @DisplayName("출발 기록 없는 도착·범위 밖 소요시간은 표본이 아니다")
    void ignoresOutOfRange() {
        adapter.recordArrived(LINE, DIR, ST, "nodep", T0);
        adapter.recordDeparted(LINE, DIR, ST, "slow", T0);
        adapter.recordArrived(LINE, DIR, ST, "slow", T0.plusSeconds(901));

        assertThat(adapter.recentSeconds(LINE, DIR, ST)).isEmpty();
    }

    @Test
    @DisplayName("15분 넘은 출발 기록(같은 번호의 이전 운행)은 새 출발이 덮는다")
    void staleDepartureIsReplaced() {
        adapter.recordDeparted(LINE, DIR, ST, "3442", T0);
        adapter.recordDeparted(LINE, DIR, ST, "3442", T0.plusSeconds(16 * 60));

        assertThat(adapter.departedAt(LINE, DIR, ST, "3442")).contains(T0.plusSeconds(16 * 60));
    }
}
