package watoo.grd.nextroute.infrastructure.adapter.out.cache.journey;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import watoo.grd.nextroute.application.journey.event.JourneyVersionChangedEvent;
import watoo.grd.nextroute.application.journey.port.out.JourneyVersionPort;
import watoo.grd.nextroute.infrastructure.adapter.out.persistence.journey.JourneyVersionPersistenceAdapter;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 조건부 조회의 버전 확인을 Redis 에 캐시한다(#427 처방 4차). 토큰 세대 캐시(#442)와 같은 모양이다.
 *
 * <p><b>왜.</b> 앱은 여정 화면에서 10초마다 마지막 버전을 들고 묻는다(여정 6,000 이면 초당 약 600건). 답은 거의 언제나
 * "바뀐 것 없음(204)"인데, 그걸 가르는 버전 한 줄 읽기가 매번 커넥션을 빌려 api 풀(20)을 채웠다 — DB 시간 1위(19.6%),
 * 커넥션 대기 p95 481ms. 버전은 사용자 명령·평가 결과·진행 보고가 있을 때만 바뀐다.
 *
 * <p><b>최신성.</b> 값을 바꾸는 쓰기가 같은 트랜잭션에서 {@link JourneyVersionChangedEvent} 를 내고, 커밋 직후 여기서
 * DB 의 새 값으로 <b>덮어쓴다</b>. 읽는 쪽은 비어 있을 때만 채운다(SET NX) — 옛 값을 읽어 둔 요청이 덮어쓰기 뒤에 늦게
 * 채워도 새 값을 지우지 못한다. 덮어쓰기 둘이 엇갈리는 경우(먼저 읽은 쪽이 나중에 쓰는 경우)는 스크립트가 막는다 —
 * 명령·평가 번호는 오르기만 하므로 <b>둘 중 하나라도 작아지는 덮어쓰기는 버린다</b>. 덮어쓰기마저 실패하면(Redis 장애)
 * TTL(30초, 사용자 결정)이 안전망이다 — 그 동안 앱이 바뀐 화면을 늦게 받는다.
 *
 * <p><b>소유자.</b> 칸은 여정마다 하나고 소유자를 함께 담는다. 남의 여정을 물으면 소유자가 달라 "없음"으로 답하고,
 * 그러면 컨트롤러의 전체 경로가 예전처럼 404 를 낸다. 지워진(탈퇴) 여정은 {@link #DELETED} 로 담는다.
 *
 * <p>Redis 장애는 DB 조회로 내려앉힌다 — 캐시 때문에 조회가 실패하면 안 된다.
 */
@Slf4j
@Primary
@Component
public class CachedJourneyVersionAdapter implements JourneyVersionPort {

    static final String KEY_PREFIX = "journey:ver:";
    /** 읽는 쪽이 본 "없음". 아직 안 만든 번호일 수도 있어, 만드는 쓰기가 덮을 수 있다. */
    static final String ABSENT = "none";
    /**
     * 쓰는 쪽이 커밋 뒤 본 "없음" — 지워진 여정이다. 지운 여정은 되살아나지 않으므로 이 값은 덮지 않는다(늦게 도착한 옛
     * 덮어쓰기가 탈퇴한 사용자의 여정을 되살리지 못하게).
     */
    static final String DELETED = "gone";

    /**
     * 덮어쓰기. 지금 칸의 명령·평가 번호 중 하나라도 새 값보다 크면 버린다(늦게 도착한 옛 값). 지워진 여정이면 버린다.
     * 읽는 쪽의 "없음"·깨진 값은 덮는다. ARGV: 1=새 값, 2=명령 번호, 3=평가 번호, 4=수명(초).
     */
    private static final DefaultRedisScript<Long> OVERWRITE_IF_NOT_OLDER = new DefaultRedisScript<>("""
            local cur = redis.call('GET', KEYS[1])
            if cur == 'gone' then
              return 0
            end
            if cur then
              local c, e = string.match(cur, '^%d+:(%d+):(%d+):')
              if c and (tonumber(c) > tonumber(ARGV[2]) or tonumber(e) > tonumber(ARGV[3])) then
                return 0
              end
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[4])
            return 1
            """, Long.class);

    private final JourneyVersionPersistenceAdapter delegate;
    private final RedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public CachedJourneyVersionAdapter(JourneyVersionPersistenceAdapter delegate,
                                       RedisTemplate<String, String> redisTemplate,
                                       MeterRegistry meterRegistry,
                                       @Value("${journey.query.version-cache.ttl-seconds:30}") long ttlSeconds) {
        this(delegate, redisTemplate, meterRegistry, ttlSeconds, Clock.systemUTC());
    }

    CachedJourneyVersionAdapter(JourneyVersionPersistenceAdapter delegate,
                                RedisTemplate<String, String> redisTemplate,
                                MeterRegistry meterRegistry, long ttlSeconds, Clock clock) {
        this.delegate = delegate;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.clock = clock;
    }

    @Override
    public Optional<Entry> find(long journeyId) {
        if (disabled()) {
            return delegate.find(journeyId);
        }
        String cached;
        try {
            cached = redisTemplate.opsForValue().get(KEY_PREFIX + journeyId);
        } catch (RuntimeException e) {
            record("error");
            log.warn("[JourneyVersionCache] 읽기 실패 — DB 로 내려간다: {}", e.getMessage());
            return delegate.find(journeyId);
        }
        if (cached != null) {
            Optional<Entry> parsed = parse(cached);
            if (parsed != null) {
                record("hit");
                return parsed;
            }
        }
        record("miss");
        Optional<Entry> fresh = delegate.find(journeyId);
        try {
            // 비어 있을 때만 채운다 — 커밋 직후 덮어쓴 새 값을, 그 전에 옛 값을 읽어 둔 요청이 지우지 못하게.
            redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + journeyId, encode(fresh), ttl);
        } catch (RuntimeException e) {
            record("error");
            log.warn("[JourneyVersionCache] 채우기 실패: {}", e.getMessage());
        }
        return fresh;
    }

    /**
     * 값이 바뀐 트랜잭션이 커밋된 뒤 DB 의 새 값으로 덮어쓴다. 롤백되면 불리지 않는다.
     *
     * <p>{@code fallbackExecution} — 트랜잭션 없이 낸 사건(조건부 UPDATE 를 자동 커밋으로 부른 경우)도 이미 반영된
     * 뒤라 곧바로 덮어써도 된다. 빠뜨리는 것보다 낫다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChanged(JourneyVersionChangedEvent event) {
        if (disabled()) {
            return;
        }
        for (Long journeyId : event.journeyIds()) {
            refresh(journeyId);
        }
    }

    private void refresh(Long journeyId) {
        try {
            // 커밋 직후라 지금이 곧 변경 커밋 시각이다(전달 지연 지표의 기준점). 읽는 쪽이 채운 칸에는 이 값이 없다.
            Optional<Entry> fresh = delegate.find(journeyId).map(e -> e.withChangedAt(clock.millis()));
            String key = KEY_PREFIX + journeyId;
            if (fresh.isEmpty()) {
                // 지운 여정은 되살아나지 않는다 — 번호 비교 없이 "지워짐"으로 덮는다.
                redisTemplate.opsForValue().set(key, DELETED, ttl);
            } else {
                Entry e = fresh.get();
                redisTemplate.execute(OVERWRITE_IF_NOT_OLDER, List.of(key), encode(fresh),
                        String.valueOf(e.commandVersion()), String.valueOf(e.evaluationVersion()),
                        String.valueOf(ttl.toSeconds()));
            }
            record("refreshed");
        } catch (RuntimeException e) {
            // 남은 안전망은 TTL 뿐이다. 그 동안 앱이 바뀐 화면을 늦게 받으니 눈에 띄게 남긴다.
            record("refresh-error");
            log.error("[JourneyVersionCache] 버전 변경 반영 실패 journeyId={} — 최대 {}초 옛 화면이 갈 수 있다: {}",
                    journeyId, ttl.toSeconds(), e.getMessage());
        }
    }

    private boolean disabled() {
        return ttl.isZero() || ttl.isNegative();
    }

    /** 모르는 칸 표시. */
    private static final String UNKNOWN = "-";

    /**
     * {@code 소유자:명령:평가:단계종류:변경시각(ms)} 또는 {@link #ABSENT}. 모르는 칸은 {@code -}. 스크립트가 앞 세 칸을
     * 읽으므로 순서를 바꾸지 않는다.
     */
    static String encode(Optional<Entry> entry) {
        if (entry.isEmpty()) {
            return ABSENT;
        }
        Entry e = entry.get();
        return e.userId() + ":" + e.commandVersion() + ":" + e.evaluationVersion() + ":"
                + (e.currentLegType() == null ? UNKNOWN : e.currentLegType()) + ":"
                + (e.changedAtEpochMs() == null ? UNKNOWN : e.changedAtEpochMs());
    }

    /** @return 알 수 없는 값이면 null(캐시를 믿지 않고 DB 로 간다) */
    static Optional<Entry> parse(String value) {
        if (ABSENT.equals(value) || DELETED.equals(value)) {
            return Optional.empty();
        }
        String[] parts = value.split(":", -1);
        if (parts.length != 5 || parts[3].isEmpty()) {
            return null;
        }
        try {
            return Optional.of(new Entry(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]), UNKNOWN.equals(parts[3]) ? null : parts[3],
                    UNKNOWN.equals(parts[4]) ? null : Long.parseLong(parts[4])));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void record(String result) {
        meterRegistry.counter("journey.version.cache", "result", result).increment();
    }
}
