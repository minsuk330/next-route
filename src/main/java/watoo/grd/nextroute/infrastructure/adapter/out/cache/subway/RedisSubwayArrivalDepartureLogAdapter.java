package watoo.grd.nextroute.infrastructure.adapter.out.cache.subway;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.port.out.SubwayArrivalDepartureLogPort;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 도착 API 출발 기록의 Redis 구현.
 *
 * <p>{@code subway:adep:{serviceDate}:{line}:{direction}:{station}} — ZSet, 점수 = 출발 epoch 초, 값 = 행 JSON. TTL 30분
 * (떠난 열차 창 10분의 여유). 위치 출발 사건 키({@code subway:dep:*})와 일부러 나눴다.
 *
 * <p>쓰는 쪽은 worker 수집기 하나라 "지우고 더하기"에 잠금을 두지 않는다({@link RedisSubwayTrainLogAdapter} 와 같은 전제).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSubwayArrivalDepartureLogAdapter implements SubwayArrivalDepartureLogPort {

    static final String PREFIX = "subway:adep:";
    static final Duration TTL = Duration.ofMinutes(30);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void append(LocalDate serviceDate, String lineId, String direction, String stationId, Row row) {
        String key = key(serviceDate, lineId, direction, stationId);
        Set<String> existing = redisTemplate.opsForZSet().range(key, 0, -1);
        if (existing != null) {
            for (String member : existing) {
                Stored stored = parse(member);
                if (stored != null && row.trainNo().equals(stored.trainNo())) {
                    redisTemplate.opsForZSet().remove(key, member);
                }
            }
        }
        try {
            redisTemplate.opsForZSet().add(key, objectMapper.writeValueAsString(Stored.of(row)),
                    row.departedAt().getEpochSecond());
        } catch (Exception e) {
            throw new IllegalStateException("도착 출발 기록 직렬화 실패", e);
        }
        redisTemplate.expire(key, TTL);
    }

    @Override
    public List<Row> find(LocalDate serviceDate, String lineId, String direction, String stationId,
                          Instant from, Instant to) {
        Set<String> members = redisTemplate.opsForZSet().rangeByScore(
                key(serviceDate, lineId, direction, stationId), from.getEpochSecond(), to.getEpochSecond());
        if (members == null) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>(members.size());
        for (String member : members) {
            Stored stored = parse(member);
            if (stored != null) {
                rows.add(stored.toRow());
            }
        }
        return rows;
    }

    static String key(LocalDate serviceDate, String lineId, String direction, String stationId) {
        return PREFIX + serviceDate + ":" + lineId + ":" + direction + ":" + stationId;
    }

    private Stored parse(String member) {
        try {
            return objectMapper.readValue(member, Stored.class);
        } catch (Exception e) {
            log.debug("도착 출발 기록 해석 실패: {}", member);
            return null;
        }
    }

    record Stored(String trainNo, String destinationName, String trainType, long departedAt) {

        static Stored of(Row row) {
            return new Stored(row.trainNo(), row.destinationName(), row.trainType(), row.departedAt().getEpochSecond());
        }

        Row toRow() {
            return new Row(trainNo, destinationName, trainType, Instant.ofEpochSecond(departedAt));
        }
    }
}
