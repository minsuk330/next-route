package watoo.grd.nextroute.infrastructure.adapter.out.auth;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import watoo.grd.nextroute.application.auth.event.TokenGenerationChangedEvent;
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;

import java.time.Duration;
import java.util.OptionalInt;

/**
 * 토큰 세대 확인을 Redis 에 1분 캐시한다(#427 3차 처방).
 *
 * <p><b>왜.</b> 인증된 요청마다 {@code users.token_generation} 을 DB 에서 읽는다. 쿼리는 0.09ms 로 빠르지만 그때마다
 * 커넥션을 빌린다 — 지하철 사다리 6,000 여정에서 앱 조회 초당 약 600건이 같은 수만큼 커넥션을 빌려, 평가기와 한
 * 풀(20)을 다투다 커넥션 대기 p95 246ms 가 됐다. 세대는 로그아웃·탈퇴 때만 바뀌므로 거의 언제나 같은 답이다.
 *
 * <p><b>최신성.</b> 세대가 바뀌는 두 곳(로그아웃·탈퇴)이 커밋 직후 {@link TokenGenerationChangedEvent} 를 내고, 여기서
 * DB 의 새 값으로 <b>덮어쓴다</b>. 읽는 쪽은 비어 있을 때만 채운다(SET NX). 그래서 "옛 값을 읽어 둔 요청이 덮어쓰기
 * 뒤에 늦게 채우는" 경합에도 새 값이 지워지지 않는다. 덮어쓰기마저 실패하면(Redis 장애) TTL 1분이 안전망이다 —
 * 그 동안만 로그아웃·탈퇴한 토큰이 통할 수 있다(사용자 결정 2026-10-15).
 *
 * <p>여러 인스턴스가 같은 Redis 를 보므로 어느 인스턴스에서 로그아웃해도 모두에 바로 반영된다(메모리 캐시였다면
 * 다른 인스턴스는 TTL 만큼 늦었다). Redis 장애는 DB 조회로 내려앉힌다 — 인증이 캐시 때문에 실패하면 안 된다.
 */
@Slf4j
@Primary
@Component
public class CachedTokenGenerationAdapter implements TokenGenerationPort {

    static final String KEY_PREFIX = "auth:tokengen:";
    /** 탈퇴했거나 없는 사용자. 없음도 캐시해야 지운 계정의 토큰이 매번 DB 를 부르지 않는다. */
    static final String ABSENT = "none";

    private final UserTokenGenerationAdapter delegate;
    private final RedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;
    private final Duration ttl;

    public CachedTokenGenerationAdapter(UserTokenGenerationAdapter delegate,
                                        RedisTemplate<String, String> redisTemplate,
                                        MeterRegistry meterRegistry,
                                        @Value("${auth.token-generation-cache.ttl-seconds:60}") long ttlSeconds) {
        this.delegate = delegate;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    @Override
    public OptionalInt currentGeneration(long userId) {
        if (ttl.isZero() || ttl.isNegative()) {
            return delegate.currentGeneration(userId);
        }
        String cached;
        try {
            cached = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
        } catch (RuntimeException e) {
            record("error");
            log.warn("[TokenGenerationCache] 읽기 실패 — DB 로 내려간다: {}", e.getMessage());
            return delegate.currentGeneration(userId);
        }
        if (cached != null) {
            OptionalInt parsed = parse(cached);
            if (parsed != null) {
                record("hit");
                return parsed;
            }
        }
        record("miss");
        OptionalInt fresh = delegate.currentGeneration(userId);
        try {
            // 비어 있을 때만 채운다 — 로그아웃 직후 덮어쓴 새 값을 옛 값을 읽어 둔 요청이 지우지 못하게.
            redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + userId, encode(fresh), ttl);
        } catch (RuntimeException e) {
            record("error");
            log.warn("[TokenGenerationCache] 채우기 실패: {}", e.getMessage());
        }
        return fresh;
    }

    /**
     * 세대가 바뀐 트랜잭션이 커밋된 뒤 DB 의 새 값으로 덮어쓴다. 롤백되면 불리지 않는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(TokenGenerationChangedEvent event) {
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            OptionalInt fresh = delegate.currentGeneration(event.userId());
            redisTemplate.opsForValue().set(KEY_PREFIX + event.userId(), encode(fresh), ttl);
            record("refreshed");
        } catch (RuntimeException e) {
            // 남은 안전망은 TTL 뿐이다. 그 동안 옛 토큰이 통할 수 있으니 반드시 눈에 띄게 남긴다.
            record("refresh-error");
            log.error("[TokenGenerationCache] 세대 변경 반영 실패 userId={} — 최대 {}초 옛 토큰이 통할 수 있다: {}",
                    event.userId(), ttl.toSeconds(), e.getMessage());
        }
    }

    static String encode(OptionalInt generation) {
        return generation.isPresent() ? String.valueOf(generation.getAsInt()) : ABSENT;
    }

    /** @return 알 수 없는 값이면 null(캐시를 믿지 않고 DB 로 간다) */
    static OptionalInt parse(String value) {
        if (ABSENT.equals(value)) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void record(String result) {
        meterRegistry.counter("auth.token_generation.cache", "result", result).increment();
    }
}
