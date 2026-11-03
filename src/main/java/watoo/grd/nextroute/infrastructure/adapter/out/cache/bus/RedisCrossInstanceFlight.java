package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.port.out.CrossInstanceFlightPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.Outcome;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CrossInstanceFlightPort} 의 Redis 구현.
 *
 * <p>키: 잠금 {@code bus:flight:lock:{type}:{key}}, 결과 {@code bus:flight:result:{type}:{key}:{token}}.
 * 잠금 획득은 Lua 하나로 "SET NX 성공이면 내 토큰, 실패면 보유자 토큰"을 돌려준다 — 대기자가 어느 결과 키를 볼지
 * 알아야 하기 때문이다. 해제는 토큰이 같을 때만 DEL 한다(만료 뒤 늦게 끝난 옛 리더가 새 리더의 잠금을 지우지 않게,
 * {@code RedisLeaderLease.RELEASE} 와 같은 모양).
 */
@Component
public class RedisCrossInstanceFlight implements CrossInstanceFlightPort {

    static final String LOCK_PREFIX = "bus:flight:lock:";
    static final String RESULT_PREFIX = "bus:flight:result:";

    private static final RedisScript<String> LEAD = new DefaultRedisScript<>("""
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
              return ARGV[1]
            end
            return redis.call('GET', KEYS[1])
            """, String.class);

    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisCrossInstanceFlight(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Lead tryLead(String type, String key, Duration lockTtl) {
        String token = UUID.randomUUID().toString();
        String lockKey = lockKey(type, key);
        String holder = redisTemplate.execute(LEAD, List.of(lockKey), token, String.valueOf(lockTtl.toMillis()));
        if (holder == null) {
            // SET NX 실패 직후 잠금이 만료된 틈 — 한 번 더 시도한다. 또 비면 "잠금 없음"으로 돌려 호출자가 정리한다.
            holder = redisTemplate.execute(LEAD, List.of(lockKey), token, String.valueOf(lockTtl.toMillis()));
        }
        if (holder == null) {
            return new Lead(false, null);
        }
        return new Lead(token.equals(holder), holder);
    }

    @Override
    public void finish(String type, String key, String token, Outcome outcome, List<?> data, Duration resultTtl) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("outcome", outcome.name());
        node.set("data", objectMapper.valueToTree(outcome == Outcome.OK ? data : List.of()));
        try {
            redisTemplate.opsForValue().set(resultKey(type, key, token), objectMapper.writeValueAsString(node), resultTtl);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("flight result encode failed", e);
        } finally {
            // 결과를 먼저 쓰고 잠금을 푼다 — 대기자가 "잠금 없음"을 보기 전에 결과가 있어야 한다.
            redisTemplate.execute(RELEASE, List.of(lockKey(type, key)), token);
        }
    }

    @Override
    public <T> Optional<Peek<T>> peekResult(String type, String key, String token, Class<T> elementType) {
        String json = redisTemplate.opsForValue().get(resultKey(type, key, token));
        if (json == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            Outcome outcome = Outcome.valueOf(node.get("outcome").asText());
            JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, elementType);
            List<T> data = objectMapper.readValue(objectMapper.treeAsTokens(node.get("data")), listType);
            return Optional.of(new Peek<>(outcome, data));
        } catch (Exception e) {
            throw new IllegalStateException("flight result decode failed", e);
        }
    }

    @Override
    public Optional<String> currentHolder(String type, String key) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(lockKey(type, key)));
    }

    static String lockKey(String type, String key) {
        return LOCK_PREFIX + type + ":" + key;
    }

    static String resultKey(String type, String key, String token) {
        return RESULT_PREFIX + type + ":" + key + ":" + token;
    }
}
