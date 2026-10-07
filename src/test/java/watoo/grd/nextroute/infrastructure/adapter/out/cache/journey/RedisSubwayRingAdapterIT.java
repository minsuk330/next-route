package watoo.grd.nextroute.infrastructure.adapter.out.cache.journey;

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
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPhase;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 링 캐시의 <b>키·TTL·정리</b> 계약을 실 Redis 로 고정한다.
 *
 * <p>여기서 막는 사고 셋 —
 * <ul>
 *   <li><b>TTL 없는 키</b>: 수명주기 정리가 한 번이라도 새면 키가 영원히 남아 다른 캐시를 밀어낸다</li>
 *   <li><b>옛 세대가 새 링을 덮음</b>: 키에 개정본과 고정 세대가 둘 다 없으면 재탐색에서 섞인다</li>
 *   <li><b>{@code SCAN} 없이 못 지움</b>: 색인 SET 이 없으면 여정 하나 정리에 키공간 전수 조회가 된다</li>
 * </ul>
 *
 * <p>Spring 컨텍스트째 띄우는 이유는 <b>직렬화까지 진짜여야</b> 하기 때문이다. 애플리케이션의
 * {@code ObjectMapper} 로 {@link Instant}·{@link BigDecimal} 이 왕복하지 못하면 링은 저장되는데
 * 읽을 때마다 사라진다 — 따로 만든 mapper 로 시험하면 그 사고를 못 잡는다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("진행 링 캐시의 키·TTL·정리 계약")
class RedisSubwayRingAdapterIT {

    private static final Long JOURNEY_ID = 90210L;
    private static final Instant T0 = Instant.parse("2026-08-15T09:00:00Z");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired RedisSubwayRingAdapter adapter;
    @Autowired RedisTemplate<String, String> redisTemplate;
    @Autowired JourneySubwayRingProperties properties;

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
        adapter.deleteByJourney(JOURNEY_ID);
    }

    @Test
    @DisplayName("저장한 링이 그대로 되읽힌다 — 시각과 진행률이 왕복한다")
    void roundTrips() {
        adapter.save(ring(1, 4, 0, "0.4500"));

        SubwayRingState found = adapter.find(JOURNEY_ID, 1, 4, 0).orElseThrow();

        assertThat(found.segmentProgress()).isEqualByComparingTo("0.45");
        assertThat(found.progressBaseAt()).isEqualTo(T0);
        assertThat(found.estimatedNextStationAt()).isEqualTo(T0.plusSeconds(90));
        assertThat(found.phase()).isEqualTo(SubwayRingPhase.RIDE_SUBWAY);
        assertThat(found.quality()).isEqualTo(SubwayRingQuality.NORMAL);
    }

    @Test
    @DisplayName("키에 TTL 이 있다 — 색인에도 있다")
    void keysCarryTtl() {
        adapter.save(ring(1, 4, 0, "0.4500"));

        String key = RedisSubwayRingAdapter.key(JOURNEY_ID, 1, 4, 0);
        assertThat(redisTemplate.getExpire(key))
                .isPositive()
                .isLessThanOrEqualTo(properties.getTtl().toSeconds());
        assertThat(redisTemplate.getExpire(RedisSubwayRingAdapter.indexKey(JOURNEY_ID)))
                .isPositive();
    }

    @Test
    @DisplayName("개정본과 고정 세대가 다르면 다른 키다 — 옛 worker 가 새 링을 덮지 않는다")
    void generationsDoNotCollide() {
        adapter.save(ring(1, 4, 0, "0.4500"));
        adapter.save(ring(2, 4, 0, "0.1000"));
        adapter.save(ring(1, 4, 3, "0.8000"));

        assertThat(adapter.find(JOURNEY_ID, 1, 4, 0).orElseThrow().segmentProgress())
                .isEqualByComparingTo("0.45");
        assertThat(adapter.find(JOURNEY_ID, 2, 4, 0).orElseThrow().segmentProgress())
                .isEqualByComparingTo("0.10");
        assertThat(adapter.find(JOURNEY_ID, 1, 4, 3).orElseThrow().segmentProgress())
                .isEqualByComparingTo("0.80");
    }

    @Test
    @DisplayName("여정 색인으로 세대를 가리지 않고 한 번에 지운다 — SCAN 을 돌리지 않는다")
    void deletesEveryGenerationOfTheJourney() {
        adapter.save(ring(1, 4, 0, "0.4500"));
        adapter.save(ring(2, 4, 0, "0.1000"));
        adapter.save(ring(2, 6, 1, "0.2000"));

        assertThat(adapter.deleteByJourney(JOURNEY_ID)).isEqualTo(3);

        assertThat(adapter.find(JOURNEY_ID, 1, 4, 0)).isEmpty();
        assertThat(adapter.find(JOURNEY_ID, 2, 4, 0)).isEmpty();
        assertThat(adapter.find(JOURNEY_ID, 2, 6, 1)).isEmpty();
        assertThat(redisTemplate.hasKey(RedisSubwayRingAdapter.indexKey(JOURNEY_ID))).isFalse();
    }

    @Test
    @DisplayName("지울 것이 없어도 조용히 0 이다 — 종료 경로를 막지 않는다")
    void deleteIsIdempotent() {
        assertThat(adapter.deleteByJourney(JOURNEY_ID)).isZero();
    }

    @Test
    @DisplayName("링 버전(#307)이 왕복하고, 버전 칸이 없는 배포 전 값은 0 으로 읽힌다")
    void guidanceVersionRoundTripsAndOldValuesReadAsZero() {
        adapter.save(ring(1, 4, 0, "0.4500").withGuidanceVersion(1_791_374_000_000L));
        assertThat(adapter.find(JOURNEY_ID, 1, 4, 0).orElseThrow().guidanceVersion())
                .isEqualTo(1_791_374_000_000L);

        String key = RedisSubwayRingAdapter.key(JOURNEY_ID, 1, 4, 0);
        String withoutVersion = redisTemplate.opsForValue().get(key).replaceAll(",\\s*\"guidanceVersion\"\\s*:\\s*\\d+", "");
        assertThat(withoutVersion).doesNotContain("guidanceVersion");
        redisTemplate.opsForValue().set(key, withoutVersion);

        SubwayRingState old = adapter.find(JOURNEY_ID, 1, 4, 0).orElseThrow();
        assertThat(old.guidanceVersion()).isZero();
        assertThat(old.segmentProgress()).isEqualByComparingTo("0.45");
    }

    @Test
    @DisplayName("깨진 값은 없는 것으로 본다 — 다음 tick 이 다시 만든다")
    void brokenValueReadsAsMissing() {
        redisTemplate.opsForValue().set(RedisSubwayRingAdapter.key(JOURNEY_ID, 1, 4, 0), "{{{");

        Optional<SubwayRingState> found = adapter.find(JOURNEY_ID, 1, 4, 0);

        assertThat(found).isEmpty();
    }

    private SubwayRingState ring(int routeRevision, int legIndex, int bindingVersion,
                                 String progress) {
        return new SubwayRingState(JOURNEY_ID, routeRevision, legIndex, bindingVersion,
                2, "1002000202", "역B", "하차역", 2,
                new BigDecimal(progress), T0, new BigDecimal("0.90"),
                T0.plusSeconds(90), T0.plusSeconds(120),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, T0, T0);
    }
}
