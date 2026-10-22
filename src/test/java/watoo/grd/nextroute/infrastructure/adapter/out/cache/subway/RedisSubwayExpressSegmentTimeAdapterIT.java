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

import static org.assertj.core.api.Assertions.assertThat;

/** 급행 구간 실측 표본의 Redis 계약 — 최신순 9개, 구간별 분리, TTL(#497). */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("급행 구간 실측 소요시간 캐시 계약")
class RedisSubwayExpressSegmentTimeAdapterIT {

    private static final String LINE = "1001";
    private static final String DIR = "상행";
    private static final String ANYANG = "1001080147";
    private static final String GASAN = "1001080142";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired RedisSubwayExpressSegmentTimeAdapter adapter;
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
        redisTemplate.delete(RedisSubwayExpressSegmentTimeAdapter.key(LINE, DIR, ANYANG, GASAN));
        redisTemplate.delete(RedisSubwayExpressSegmentTimeAdapter.key(LINE, DIR, GASAN, ANYANG));
    }

    @Test
    @DisplayName("표본은 최신순 9개만 남고 TTL 이 걸린다")
    void keepsLatestNine() {
        for (long s = 400; s < 412; s++) {
            adapter.record(LINE, DIR, ANYANG, GASAN, s);
        }

        assertThat(adapter.recentSeconds(LINE, DIR, ANYANG, GASAN))
                .containsExactly(411L, 410L, 409L, 408L, 407L, 406L, 405L, 404L, 403L);
        assertThat(redisTemplate.getExpire(RedisSubwayExpressSegmentTimeAdapter.key(LINE, DIR, ANYANG, GASAN)))
                .isPositive();
    }

    @Test
    @DisplayName("구간은 출발역·도착역 순서까지 가른다")
    void segmentsAreDirected() {
        adapter.record(LINE, DIR, ANYANG, GASAN, 427);

        assertThat(adapter.recentSeconds(LINE, DIR, GASAN, ANYANG)).isEmpty();
        assertThat(adapter.recentSeconds(LINE, DIR, ANYANG, GASAN)).containsExactly(427L);
    }
}
