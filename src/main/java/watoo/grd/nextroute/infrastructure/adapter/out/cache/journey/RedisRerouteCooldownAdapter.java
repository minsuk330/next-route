package watoo.grd.nextroute.infrastructure.adapter.out.cache.journey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.port.out.RerouteCooldownPort;

import java.time.Duration;

/**
 * 재탐색 cooldown 을 Redis 키 하나로 표현한다.
 *
 * <p><b>메모리가 아니라 Redis 인 이유</b> — worker 가 둘이면 메모리 cooldown 은 worker 마다 따로
 * 돌아 같은 환승이 최대 2배로 통과한다. shadow 에서 잰 수가 ③의 실제 외부 호출 수와 달라지면
 * 측정의 의미가 없다.
 *
 * <p><b>fail-closed</b> — Redis 장애면 통과시키지 않는다({@code RedisBusApiQuota} 와 같은 선례).
 * 여기서 열어 두면 ③이 켜진 뒤 캐시 장애가 곧 ODsay 요금이 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRerouteCooldownAdapter implements RerouteCooldownPort {

    static final String KEY_PREFIX = "journey:reroute:cooldown:";

    private final RedisTemplate<String, String> redisTemplate;
    private final JourneyRerouteProperties properties;

    /**
     * 키를 심는 데 성공한 쪽만 통과한다.
     *
     * <p>{@code setIfAbsent(key, value, ttl)} <b>한 문장</b>으로 끝낸다. 심기와 TTL 을 두 문장으로
     * 나누면 그 사이에 죽었을 때 만료 없는 키가 남아 그 환승이 영영 막힌다.
     */
    @Override
    public boolean tryAdmit(Long journeyId, int routeRevision, int transferIndex, Scope scope) {
        Duration cooldown = properties.getTrigger().getCooldown();
        if (cooldown.isZero()) {
            // 사실상 끈 상태. Redis 는 TTL 0 을 거부하므로 키를 심지 않고 그냥 통과시킨다.
            // 원시 발생률만 보고 싶을 때 쓰는 설정이다.
            return true;
        }
        String key = key(journeyId, routeRevision, transferIndex, scope);
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(key, "1", cooldown));
        } catch (Exception e) {
            log.error("[JourneyReroute] cooldown 확인 실패 — fail-closed [{}]: {}",
                    key, e.getMessage());
            return false;
        }
    }

    /**
     * 개정본을 키에 넣는다. 수락으로 경로가 바뀌면 이전 개정본의 cooldown 이 새 경로의 첫
     * 재탐색을 막아서는 안 된다 — 그때는 완전히 다른 상황이다.
     *
     * <p>용도({@link Scope})도 키에 넣는다. 섞으면 아무것도 만들지 않는 shadow 방아쇠가 잠금을
     * 태워 사용자 요청이 거절된다(D6).
     */
    private String key(Long journeyId, int routeRevision, int transferIndex, Scope scope) {
        return KEY_PREFIX + scope.name().toLowerCase() + ":"
                + journeyId + ":" + routeRevision + ":" + transferIndex;
    }
}
