package watoo.grd.nextroute.common.lock;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 키 하나로 리더를 정한다: {@code SET key token NX PX ttl}.
 *
 * <p><b>갱신은 전용 스레드가 TTL/3 마다 한다.</b> 수집 주기(15초)에 묶어 갱신하면 TTL 을 그보다 길게
 * 잡아야 하고, 리더가 죽었을 때 인계까지의 공백이 그만큼 길어진다. 갱신·해제는 토큰을 비교하는 Lua 로
 * 한다 — 만료 뒤 남의 잠금을 지우거나 늘리지 않게.
 *
 * <p>{@code journey.navigation.reeval.leader-lease.enabled} 가 꺼져 있으면 갱신 스레드를 띄우지 않고
 * <b>항상 리더</b>라고 답한다. 지금처럼 worker 가 한 대인 운영에서 동작을 바꾸지 않는다. 켜면 이 잠금을 따르는
 * 작업 <b>전부</b>가 리더 한 대로 모인다(플래그 하나 — 어긋나면 아무도 안 하거나 둘이 한다): 지하철 수집기, 지하철
 * 변화 감지, 버스 노선 감시자, 버스 도착 수집기, 버스 위치 수집기, 버스 도착 알림 폴러. 켠 상태에서 Redis 가
 * 죽으면 모두 "리더 아님"이 되어 멈춘다(지하철 수집과 같은 성질) — 버스 도착 알림도 예외가 아니다.
 */
@Slf4j
@Component
public class RedisLeaderLease implements LeaderLease {

    /**
     * 얻거나 늘리기를 한 번에 한다. 내 토큰이면 만료만 늘리고, 비어 있으면 내 것으로 잡는다. 둘로 나누면
     * Redis 오류 한 번에 "잃었다"고 판단한 뒤, 아직 내 토큰이 남은 키에 SET NX 를 시도해 만료까지 아무도
     * 리더가 아닌 공백이 생긴다.
     */
    private static final RedisScript<Long> ACQUIRE_OR_RENEW = new DefaultRedisScript<>("""
            local current = redis.call('get', KEYS[1])
            if current == ARGV[1] then
              redis.call('pexpire', KEYS[1], ARGV[2])
              return 1
            end
            if not current then
              redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final boolean enabled;
    private final String key;
    private final long ttlMs;
    private final String token = UUID.randomUUID().toString();

    /** 로컬 마감(System.nanoTime 기준). 이 시각 전까지만 리더라고 답한다. */
    private volatile long deadlineNanos = Long.MIN_VALUE;
    private volatile boolean held;
    private final AtomicLong epoch = new AtomicLong();
    private ScheduledExecutorService renewer;
    /** worker 프로필인지. 생성자로 받지 않는 이유: 시험이 프로필 없이 직접 만든다. */
    private volatile boolean worker = true;

    /**
     * @param enabled 켜도 worker 프로필이 아니면 경쟁하지 않고 늘 "리더 아님"이다. api 인스턴스가 키를 잡아
     *                worker 의 수집·감시를 멈추게 하는 사고를 막는다(공용 env 앵커에 플래그가 들어가도).
     */
    public RedisLeaderLease(RedisTemplate<String, String> redisTemplate,
                            MeterRegistry registry,
                            @Value("${journey.navigation.reeval.leader-lease.enabled:false}") boolean enabled,
                            @Value("${journey.navigation.reeval.leader-lease.key:lease:realtime-watch}") String key,
                            @Value("${journey.navigation.reeval.leader-lease.ttl-ms:10000}") long ttlMs) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.key = key;
        this.ttlMs = ttlMs;
        Gauge.builder("journey.reeval.leader", this, lease -> lease.isLeader() ? 1 : 0)
                .description("1 if this instance holds the realtime-watch lease")
                .register(registry);
    }

    @Autowired(required = false)
    void setEnvironment(Environment environment) {
        this.worker = environment == null || environment.acceptsProfiles(Profiles.of("worker"));
    }

    @PostConstruct
    void start() {
        if (!enabled || !worker) {
            return;
        }
        renewer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "leader-lease");
            t.setDaemon(true);
            return t;
        });
        renewer.scheduleWithFixedDelay(this::tick, 0, Math.max(1, ttlMs / 3), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (renewer == null) {
            return;
        }
        renewer.shutdownNow();
        try {
            redisTemplate.execute(RELEASE, List.of(key), token);
        } catch (Exception e) {
            log.debug("[LeaderLease] 해제 실패(만료로 풀림): {}", e.getMessage());
        }
    }

    @Override
    public boolean isLeader() {
        if (!enabled) {
            return true;
        }
        return worker && held && System.nanoTime() < deadlineNanos;
    }

    @Override
    public long epoch() {
        return epoch.get();
    }

    /** 한 번 얻거나 늘린다. 시험이 직접 부를 수 있게 열어 둔다. */
    void tick() {
        long attemptStarted = System.nanoTime();
        boolean ok;
        try {
            Long result = redisTemplate.execute(ACQUIRE_OR_RENEW, List.of(key), token, String.valueOf(ttlMs));
            ok = result != null && result == 1L;
        } catch (Exception e) {
            log.warn("[LeaderLease] Redis 오류 — 리더 아님으로 둔다: {}", e.getMessage());
            ok = false;
        }
        if (ok) {
            // 마감은 시도를 <b>시작한</b> 시각부터 센다. 응답이 늦게 와도 Redis 쪽 만료보다 앞서 끝나게.
            deadlineNanos = attemptStarted + TimeUnit.MILLISECONDS.toNanos(ttlMs * 2 / 3);
            if (!held) {
                held = true;
                epoch.incrementAndGet();
                log.info("[LeaderLease] 리더가 됨 — key={}", key);
            }
        } else if (held) {
            held = false;
            epoch.incrementAndGet();
            log.warn("[LeaderLease] 리더 자격을 잃음 — key={}", key);
        }
    }
}
