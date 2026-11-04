package watoo.grd.nextroute.infrastructure.adapter.out.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RedisTestSupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("지역 버스 차단기 공유 저장소")
class RedisSharedBlockStoreIT {

    private LettuceConnectionFactory factory;
    private RedisTemplate<String, String> redis;

    @BeforeEach
    void setUp() {
        factory = RedisTestSupport.factory();
        redis = RedisTestSupport.template(factory);
        redis.delete(RedisSharedBlockStore.KEY_PREFIX + "gyeonggi-bus-arrival");
        redis.delete(RedisSharedBlockStore.KEY_PREFIX + "tago-bus-arrival");
    }

    @AfterEach
    void tearDown() {
        redis.delete(RedisSharedBlockStore.KEY_PREFIX + "gyeonggi-bus-arrival");
        redis.delete(RedisSharedBlockStore.KEY_PREFIX + "tago-bus-arrival");
        factory.destroy();
    }

    @Test
    @DisplayName("한 서버가 차단하면 다른 서버도 차단으로 보고, KST 자정이 지나면 풀린다")
    void sharedUntilKstMidnight() {
        Clock day1 = Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC);   // KST 19:00
        Clock day2 = Clock.fixed(Instant.parse("2026-11-03T15:30:00Z"), ZoneOffset.UTC);   // KST 다음날 00:30
        RedisSharedBlockStore a = new RedisSharedBlockStore(redis, day1);
        RedisSharedBlockStore b = new RedisSharedBlockStore(redis, day1);
        RedisSharedBlockStore tomorrow = new RedisSharedBlockStore(redis, day2);

        a.block("gyeonggi-bus-arrival");

        assertThat(b.isBlocked("gyeonggi-bus-arrival")).contains(true);
        assertThat(b.isBlocked("tago-bus-arrival")).contains(false);
        assertThat(tomorrow.isBlocked("gyeonggi-bus-arrival")).as("날짜가 바뀌면 차단 아님").contains(false);
        assertThat(a.handles("seoul-subway")).isFalse();
    }

    @Test
    @DisplayName("ApiCallBlocker 는 공유 저장소가 맡는 이름만 거기서 읽고, 저장소 오류면 JVM 상태로 내려간다")
    void blockerFallsBackToLocalOnStoreError() {
        SharedBlockStore broken = new SharedBlockStore() {
            @Override public boolean handles(String apiName) { return apiName.equals("gyeonggi-bus-arrival"); }
            @Override public Optional<Boolean> isBlocked(String apiName) { return Optional.empty(); }
            @Override public void block(String apiName) { }
        };
        ApiCallBlocker blocker = new ApiCallBlocker(new org.springframework.beans.factory.ObjectProvider<SharedBlockStore>() {
            @Override public SharedBlockStore getObject(Object... args) { return broken; }
            @Override public SharedBlockStore getIfAvailable() { return broken; }
            @Override public SharedBlockStore getIfUnique() { return broken; }
            @Override public SharedBlockStore getObject() { return broken; }
        });

        assertThat(blocker.isBlocked("gyeonggi-bus-arrival")).isFalse();
        blocker.block("gyeonggi-bus-arrival");
        assertThat(blocker.isBlocked("gyeonggi-bus-arrival")).as("저장소가 답을 못 주면 JVM 상태(차단)").isTrue();
    }

    @Test
    @DisplayName("공유 저장소가 '차단 아님'이어도 이 서버의 JVM 차단은 유지된다(공유 쓰기 실패 대비)")
    void localBlockSurvivesRemoteFalse() {
        SharedBlockStore saysNo = new SharedBlockStore() {
            @Override public boolean handles(String apiName) { return true; }
            @Override public Optional<Boolean> isBlocked(String apiName) { return Optional.of(false); }
            @Override public void block(String apiName) { /* 쓰기 실패를 흉내: 아무것도 안 함 */ }
        };
        ApiCallBlocker blocker = new ApiCallBlocker(new org.springframework.beans.factory.ObjectProvider<SharedBlockStore>() {
            @Override public SharedBlockStore getObject(Object... args) { return saysNo; }
            @Override public SharedBlockStore getIfAvailable() { return saysNo; }
            @Override public SharedBlockStore getIfUnique() { return saysNo; }
            @Override public SharedBlockStore getObject() { return saysNo; }
        });

        blocker.block("tago-bus-arrival");

        assertThat(blocker.isBlocked("tago-bus-arrival")).isTrue();
    }
}
