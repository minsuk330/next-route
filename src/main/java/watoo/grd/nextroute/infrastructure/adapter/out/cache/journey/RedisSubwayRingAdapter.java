package watoo.grd.nextroute.infrastructure.adapter.out.cache.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.journey.port.out.SubwayRingPort;

import java.util.List;
import java.util.Optional;

/**
 * 진행 링의 Redis 구현.
 *
 * <h2>키에 개정본과 고정 세대를 <b>둘 다</b> 넣는다</h2>
 *
 * <p>재탐색이 개정본을 올리면 같은 {@code legIndex} 의 단계가 복제되고, 계승은 고정 세대를
 * 0 으로 되돌린다. 하나만 넣으면 옛 worker 가 계산해 둔 링이 새 링을 덮는다.
 *
 * <h2>여정 색인을 따로 든다</h2>
 *
 * <p>여정이 끝날 때 지울 키를 찾으려고 {@code SCAN} 을 돌리면 여정 하나당 키공간 전수 조회가
 * 된다. 저장할 때 색인 SET 에 넣어 두고, 지울 때 그 SET 만 읽는다.
 *
 * <h2>저장은 한 문장이어야 한다</h2>
 *
 * <p>키 쓰기·TTL·색인 등록이 세 왕복으로 쪼개지면 그 사이에서 죽었을 때 <b>TTL 없는 키</b>나
 * <b>색인에 없는 키</b>가 남는다. 둘 다 영원히 남아 {@code allkeys-lru} 가 다른 캐시를 밀어낸다.
 * 그래서 Lua 로 묶는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSubwayRingAdapter implements SubwayRingPort {

    static final String KEY_PREFIX = "subway:ring:";
    static final String INDEX_PREFIX = "subway:ring:index:";

    /** 링 값·TTL·색인 등록을 한 원자 단위로 묶는다. 색인에도 같은 TTL 을 건다. */
    private static final RedisScript<Long> SAVE = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            redis.call('SADD', KEYS[2], KEYS[1])
            redis.call('EXPIRE', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    /** 색인이 가리키는 키를 전부 지우고 색인 자신도 지운다. */
    private static final RedisScript<Long> DELETE_BY_JOURNEY = new DefaultRedisScript<>("""
            local members = redis.call('SMEMBERS', KEYS[1])
            local removed = 0
            if #members > 0 then
              removed = redis.call('DEL', unpack(members))
            end
            redis.call('DEL', KEYS[1])
            return removed
            """, Long.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final JourneySubwayRingProperties properties;

    @Override
    public void save(SubwayRingState state) {
        String key = key(state.journeyId(), state.routeRevision(), state.legIndex(),
                state.bindingVersion());
        try {
            redisTemplate.execute(SAVE,
                    List.of(key, indexKey(state.journeyId())),
                    objectMapper.writeValueAsString(state),
                    String.valueOf(properties.getTtl().toSeconds()));
        } catch (Exception e) {
            // 저장 실패는 다음 tick 에 다시 시도된다. 여기서 던지면 그 tick 전체가 죽는다.
            log.warn("[SubwayRing] 링 저장 실패 [{}]: {}", key, e.getMessage());
        }
    }

    @Override
    public Optional<SubwayRingState> find(Long journeyId, int routeRevision, int legIndex,
                                          int bindingVersion) {
        String key = key(journeyId, routeRevision, legIndex, bindingVersion);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, SubwayRingState.class));
        } catch (Exception e) {
            // 없는 것으로 본다. 다음 tick 이 실시간 도착정보로 다시 만든다.
            log.warn("[SubwayRing] 링 조회 실패 — 없는 것처럼 진행한다 [{}]: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public int deleteByJourney(Long journeyId) {
        try {
            Long removed = redisTemplate.execute(DELETE_BY_JOURNEY,
                    List.of(indexKey(journeyId)));
            return removed == null ? 0 : removed.intValue();
        } catch (Exception e) {
            // TTL 이 뒤를 받친다. 정리 실패로 여정 종료를 막지 않는다.
            log.warn("[SubwayRing] 링 정리 실패 — TTL 로 만료된다 journeyId={}: {}",
                    journeyId, e.getMessage());
            return 0;
        }
    }

    static String key(Long journeyId, int routeRevision, int legIndex, int bindingVersion) {
        return KEY_PREFIX + journeyId + ":" + routeRevision + ":" + legIndex + ":" + bindingVersion;
    }

    static String indexKey(Long journeyId) {
        return INDEX_PREFIX + journeyId;
    }
}
