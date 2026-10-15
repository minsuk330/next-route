package watoo.grd.nextroute.infrastructure.adapter.out.cache.journey;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import watoo.grd.nextroute.application.journey.event.JourneyVersionChangedEvent;
import watoo.grd.nextroute.application.journey.port.out.JourneyVersionPort.Entry;
import watoo.grd.nextroute.infrastructure.adapter.out.persistence.journey.JourneyVersionPersistenceAdapter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 여정 버전 캐시의 계약(#427 처방 4차). 조용히 망가지는 것 셋: 읽는 쪽이 덮어써 새 값을 지우는 것, Redis 장애가 조회
 * 실패로 번지는 것, 꺼짐(0)인데 Redis 를 부르는 것.
 */
@DisplayName("여정 버전 캐시")
class CachedJourneyVersionAdapterTest {

    private static final long JOURNEY = 11L;
    private static final String KEY = "journey:ver:11";
    private static final Entry ENTRY = new Entry(7L, 3L, 5L, "WAIT_SUBWAY", null);

    private final JourneyVersionPersistenceAdapter delegate = mock(JourneyVersionPersistenceAdapter.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);
    private CachedJourneyVersionAdapter cache;

    @BeforeEach
    void setUp() {
        given(redis.opsForValue()).willReturn(ops);
        cache = new CachedJourneyVersionAdapter(delegate, redis, new SimpleMeterRegistry(), 30, clock);
    }

    @Test
    @DisplayName("캐시에 있으면 DB 를 부르지 않는다 — 변경 시각까지 그대로 돌려준다")
    void hitSkipsDatabase() {
        given(ops.get(KEY)).willReturn("7:3:5:WAIT_SUBWAY:1699999999000");

        assertThat(cache.find(JOURNEY)).contains(new Entry(7L, 3L, 5L, "WAIT_SUBWAY", 1_699_999_999_000L));
        verifyNoInteractions(delegate);
    }

    @Test
    @DisplayName("없음·지워짐도 캐시된다")
    void absentIsCached() {
        given(ops.get(KEY)).willReturn("none", "gone");

        assertThat(cache.find(JOURNEY)).isEmpty();
        assertThat(cache.find(JOURNEY)).isEmpty();
        verifyNoInteractions(delegate);
    }

    @Test
    @DisplayName("없으면 DB 에서 읽고 '비어 있을 때만' 채운다 — 변경 시각은 모름(-)으로, 덮어쓰지 않는다")
    void missFillsOnlyIfAbsent() {
        given(ops.get(KEY)).willReturn(null);
        given(delegate.find(JOURNEY)).willReturn(Optional.of(ENTRY));

        assertThat(cache.find(JOURNEY)).contains(ENTRY);
        verify(ops).setIfAbsent(KEY, "7:3:5:WAIT_SUBWAY:-", Duration.ofSeconds(30));
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("깨진 값(옛 형식 포함)은 믿지 않고 DB 로 간다")
    void corruptValueFallsBackToDatabase() {
        given(ops.get(KEY)).willReturn("7:3:5:1");
        given(delegate.find(JOURNEY)).willReturn(Optional.of(ENTRY));

        assertThat(cache.find(JOURNEY)).contains(ENTRY);
        verify(delegate).find(JOURNEY);
    }

    @Test
    @DisplayName("Redis 읽기가 실패하면 DB 로 내려간다 — 조회가 실패하면 안 된다")
    void redisFailureFallsBack() {
        given(ops.get(KEY)).willThrow(new RedisConnectionFailureException("down"));
        given(delegate.find(JOURNEY)).willReturn(Optional.of(ENTRY));

        assertThat(cache.find(JOURNEY)).contains(ENTRY);
    }

    @Test
    @DisplayName("채우기가 실패해도 DB 값으로 답한다")
    void fillFailureStillAnswers() {
        given(ops.get(KEY)).willReturn(null);
        given(delegate.find(JOURNEY)).willReturn(Optional.of(ENTRY));
        given(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.find(JOURNEY)).contains(ENTRY);
    }

    @Test
    @DisplayName("수명 0 이면 꺼짐 — Redis 를 전혀 부르지 않는다")
    void zeroTtlDisables() {
        CachedJourneyVersionAdapter off = new CachedJourneyVersionAdapter(delegate, redis, new SimpleMeterRegistry(),
                0, clock);
        given(delegate.find(JOURNEY)).willReturn(Optional.of(ENTRY));

        assertThat(off.find(JOURNEY)).contains(ENTRY);
        off.onChanged(JourneyVersionChangedEvent.of(JOURNEY));
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("지워진 여정은 '지워짐'으로 덮는다(번호 비교 없이)")
    void deletedOverwritesWithTombstone() {
        given(delegate.find(JOURNEY)).willReturn(Optional.empty());

        cache.onChanged(JourneyVersionChangedEvent.of(JOURNEY));

        verify(ops).set(KEY, "gone", Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("덮어쓰기 실패는 삼킨다 — 커밋된 요청을 실패로 돌리지 않는다")
    void refreshFailureIsSwallowed() {
        given(delegate.find(JOURNEY)).willThrow(new RuntimeException("db"));

        cache.onChanged(JourneyVersionChangedEvent.of(JOURNEY));
    }

    @Test
    @DisplayName("부호화·해석이 왕복한다 — 모르는 칸은 null")
    void encodeParseRoundTrip() {
        Entry withTime = new Entry(1L, 2L, 3L, "RIDE_BUS", 42L);
        Entry unknown = new Entry(1L, 2L, 3L, null, null);

        assertThat(CachedJourneyVersionAdapter.parse(CachedJourneyVersionAdapter.encode(Optional.of(withTime))))
                .contains(withTime);
        assertThat(CachedJourneyVersionAdapter.parse(CachedJourneyVersionAdapter.encode(Optional.of(unknown))))
                .contains(unknown);
        assertThat(CachedJourneyVersionAdapter.parse("garbage")).isNull();
    }
}
