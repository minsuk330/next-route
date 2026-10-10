package watoo.grd.nextroute.infrastructure.adapter.out.cache.auth;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.auth.port.out.DeviceSignupLimitPort;
import watoo.grd.nextroute.common.config.ClockConfig;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 기기 가입 한도. 출발지 IP 별 시간 단위 고정창 INCR 카운터다(구조는 {@code RedisBusApiQuota} 와 같다).
 *
 * <p><b>fail-open</b>: Redis 가 죽으면 통과시킨다. 버스 쿼터는 돈이 나가는 외부 호출이라 막는 쪽이 맞지만,
 * 여기서 막으면 Redis 장애 동안 새로 설치한 사용자 전원이 토큰을 못 받아 앱을 아예 못 쓴다. 대신 ERROR 로그와
 * 메트릭으로 장애를 드러낸다.
 *
 * <p>IPv6 는 /64 대역으로 센다. 한 가입자가 대역 안에서 주소를 돌리면 주소 단위 한도는 의미가 없다.
 */
@Slf4j
@Component
public class RedisDeviceSignupLimitAdapter implements DeviceSignupLimitPort {

    static final String KEY_PREFIX = "auth:device:signup:";
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final Duration WINDOW = Duration.ofHours(1);
    static final String METRIC = "auth.device.signup.limit";

    private final RedisTemplate<String, String> redisTemplate;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final int limitPerHour;

    public RedisDeviceSignupLimitAdapter(RedisTemplate<String, String> redisTemplate,
                                         MeterRegistry meterRegistry,
                                         Clock clock,
                                         @Value("${auth.device.signup-limit-per-hour:60}") int limitPerHour) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.limitPerHour = limitPerHour;
    }

    @Override
    public boolean tryAcquire(String clientIp) {
        String key = KEY_PREFIX + ipKey(clientIp) + ":" + hour();
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return failOpen(key, "increment returned null");
            }
            if (count == 1L) {
                redisTemplate.expire(key, WINDOW);
            }
            boolean allowed = count <= limitPerHour;
            if (!allowed) {
                meterRegistry.counter(METRIC, "result", "limited").increment();
            }
            return allowed;
        } catch (Exception e) {
            return failOpen(key, e.getMessage());
        }
    }

    private boolean failOpen(String key, String reason) {
        log.error("[DeviceSignupLimit] Redis 실패 — fail-open 으로 가입 허용 [{}]: {}", key, reason);
        meterRegistry.counter(METRIC, "result", "redis_error").increment();
        return true;
    }

    private String hour() {
        return ZonedDateTime.now(clock).withZoneSameInstant(ClockConfig.KST).format(HOUR);
    }

    /**
     * 카운터 키에 쓸 출발지. IPv4 는 그대로, IPv6 는 앞 64비트 hex.
     *
     * <p>콜론이 없는 값은 IPv4 로 보고 파싱하지 않는다. {@link InetAddress#getByName} 은 IP 형식이 아닌 문자열을
     * 받으면 DNS 를 조회하므로, 콜론이 있는(=IPv6 리터럴로만 해석되는) 값만 넘긴다.
     */
    static String ipKey(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return "unknown";
        }
        String ip = clientIp.trim();
        if (ip.indexOf(':') < 0) {
            return ip;
        }
        try {
            InetAddress addr = InetAddress.getByName(ip);
            if (addr instanceof Inet4Address) {
                return addr.getHostAddress();
            }
            byte[] bytes = addr.getAddress();
            return "v6:" + HexFormat.of().formatHex(bytes, 0, 8);
        } catch (UnknownHostException e) {
            return ip;
        }
    }
}
