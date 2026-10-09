package watoo.grd.nextroute.infrastructure.adapter.out.cache.subway;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.config.SubwayPositionProperties;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 열차 위치·사건 로그의 Redis 구현.
 *
 * <h2>색인을 둘로 나눈 이유</h2>
 *
 * <p>물어보는 축이 둘이다.
 * <ul>
 *   <li>탑승 판정: <b>역</b> 기준 — "이 역을 이 시간대에 출발한 열차가 누구인가"</li>
 *   <li>다음 역 확정: <b>열차</b> 기준 — "그 열차가 다음 역에 언제 도착했나"</li>
 * </ul>
 * 한 색인으로는 한쪽이 전수 조회가 된다. 그래서 출발은 역 키에, 도착은 열차 키에 쌓는다.
 *
 * <h2>사라져도 되는 값이다</h2>
 *
 * <p>Redis 는 비영속이고 {@code allkeys-lru} 다. 여기 있는 것이 날아가면 탑승 판정이 잠정
 * 상태에 머물 뿐, 잘못된 열차에 고정되지는 않는다. 그래서 TTL 만 관리하고 복구를 시도하지 않는다.
 *
 * <h2>쓰는 쪽이 하나라는 전제</h2>
 *
 * <p>위치 수집기 하나가 노선을 순차로 돌며 쓴다. 그래서 "읽고 지우고 쓴다"에 잠금을 두지 않았다.
 * 쓰는 곳이 늘어나면 이 전제가 깨진다 — 그때는 Lua 로 묶어야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSubwayTrainLogAdapter implements SubwayTrainLogPort {

    private static final String KEY_POSITION = "subway:pos:";
    private static final String KEY_DEPARTURE = "subway:dep:";
    private static final String KEY_ARRIVAL = "subway:arr:";
    private static final String SEP = "|";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final SubwayPositionProperties properties;

    // ── 직전 위치 ──────────────────────────────────────────────────────────

    @Override
    public Optional<SubwayTrainObservation> lastSeen(TrainIdentity train) {
        Object raw = redisTemplate.opsForHash().get(positionKey(train), train.asHashField());
        if (raw == null) {
            return Optional.empty();
        }
        return parseObservation(String.valueOf(raw));
    }

    /** 노선 해시 하나에 한 번(HMGET). 역 도착 응답이 열차·방향마다 따로 물으면 왕복이 수십 번이다. */
    @Override
    public Map<TrainIdentity, SubwayTrainObservation> lastSeenAll(Collection<TrainIdentity> trains) {
        Map<String, List<TrainIdentity>> byKey = new LinkedHashMap<>();
        for (TrainIdentity train : trains) {
            byKey.computeIfAbsent(positionKey(train), key -> new ArrayList<>()).add(train);
        }
        Map<TrainIdentity, SubwayTrainObservation> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<TrainIdentity>> entry : byKey.entrySet()) {
            List<TrainIdentity> group = entry.getValue();
            List<Object> raws = redisTemplate.opsForHash().multiGet(entry.getKey(),
                    group.stream().map(t -> (Object) t.asHashField()).toList());
            for (int i = 0; i < group.size() && raws != null && i < raws.size(); i++) {
                Object raw = raws.get(i);
                if (raw != null) {
                    TrainIdentity train = group.get(i);
                    parseObservation(String.valueOf(raw)).ifPresent(seen -> out.put(train, seen));
                }
            }
        }
        return out;
    }

    @Override
    public void remember(TrainIdentity train, SubwayTrainObservation observation) {
        String key = positionKey(train);
        redisTemplate.opsForHash().put(key, train.asHashField(), serialize(observation));
        // TTL 을 매번 다시 건다. 안 걸면 노선 키가 영원히 남아 allkeys-lru 가 다른 캐시를 밀어낸다.
        redisTemplate.expire(key, properties.getLogTtl());
    }

    // ── 사건 ──────────────────────────────────────────────────────────────

    @Override
    public void append(TrainIdentity train, SubwayTrainEvent event) {
        if (event.type() == SubwayTrainEvent.Type.DEPARTED) {
            appendDeparture(train, event);
        } else {
            appendArrival(train, event);
        }
    }

    /**
     * 같은 {@code (type, stationId)} 는 <b>품질이 오를 때만</b> 교체한다.
     *
     * <p>무조건 추가하면 한 번의 출발이 여러 건으로 보여 후보가 부풀고, 무조건 무시하면
     * 잠정({@code OBSERVED})이 확정({@code CORROBORATED})으로 올라가지 못한다.
     */
    private void appendDeparture(TrainIdentity train, SubwayTrainEvent event) {
        String key = departureKey(train.serviceDate(), train.lineId(), train.direction(), event.stationId());
        DepartureRow incoming = DepartureRow.of(train.trainNo(), event);

        Set<String> existing = redisTemplate.opsForZSet().range(key, 0, -1);
        if (existing != null) {
            for (String member : existing) {
                DepartureRow row = parse(member, DepartureRow.class);
                if (row == null || !train.trainNo().equals(row.trainNo())) {
                    continue;
                }
                SubwayTrainEvent.Quality existingQuality = row.qualityEnum();
                SubwayTrainEvent.Quality incomingQuality = incoming.qualityEnum();
                if (existingQuality != null && incomingQuality != existingQuality
                        && !incomingQuality.isStrongerThan(existingQuality)) {
                    // 들어온 것이 더 약하다. 확정을 잠정으로 되돌리지 않는다.
                    return;
                }
                redisTemplate.opsForZSet().remove(key, member);
            }
        }

        redisTemplate.opsForZSet().add(key, serialize(incoming), event.estimate().getEpochSecond());
        redisTemplate.expire(key, properties.getLogTtl());
    }

    private void appendArrival(TrainIdentity train, SubwayTrainEvent event) {
        String key = arrivalKey(train);
        ArrivalRow incoming = ArrivalRow.of(event);

        Set<String> existing = redisTemplate.opsForZSet().range(key, 0, -1);
        if (existing != null) {
            for (String member : existing) {
                ArrivalRow row = parse(member, ArrivalRow.class);
                if (row != null && event.stationId().equals(row.stationId())) {
                    redisTemplate.opsForZSet().remove(key, member);
                }
            }
        }

        redisTemplate.opsForZSet().add(key, serialize(incoming), event.estimate().getEpochSecond());
        redisTemplate.expire(key, properties.getLogTtl());
    }

    @Override
    public List<TrainDeparture> findDepartures(LocalDate serviceDate, String lineId, String direction,
                                               String stationId, Instant from, Instant to) {
        String key = departureKey(serviceDate, lineId, direction, stationId);
        Set<ZSetOperations.TypedTuple<String>> found = redisTemplate.opsForZSet()
                .rangeByScoreWithScores(key, from.getEpochSecond(), to.getEpochSecond());
        if (found == null) {
            return List.of();
        }
        List<TrainDeparture> result = new ArrayList<>(found.size());
        for (ZSetOperations.TypedTuple<String> tuple : found) {
            DepartureRow row = parse(tuple.getValue(), DepartureRow.class);
            if (row != null) {
                result.add(new TrainDeparture(row.trainNo(),
                        row.toEvent(SubwayTrainEvent.Type.DEPARTED, stationId)));
            }
        }
        return result;
    }

    @Override
    public List<SubwayTrainEvent> findArrivals(TrainIdentity train, Instant from, Instant to) {
        Set<String> found = redisTemplate.opsForZSet()
                .rangeByScore(arrivalKey(train), from.getEpochSecond(), to.getEpochSecond());
        if (found == null) {
            return List.of();
        }
        List<SubwayTrainEvent> result = new ArrayList<>(found.size());
        for (String member : found) {
            ArrivalRow row = parse(member, ArrivalRow.class);
            if (row != null) {
                result.add(row.toEvent());
            }
        }
        return result;
    }

    // ── 키 ────────────────────────────────────────────────────────────────

    private String positionKey(TrainIdentity train) {
        return KEY_POSITION + train.serviceDate() + ":" + train.lineId();
    }

    private String departureKey(LocalDate serviceDate, String lineId, String direction, String stationId) {
        return KEY_DEPARTURE + serviceDate + ":" + lineId + ":" + direction + ":" + stationId;
    }

    private String arrivalKey(TrainIdentity train) {
        return KEY_ARRIVAL + train.asKeySuffix();
    }

    // ── 직렬화 ────────────────────────────────────────────────────────────

    /**
     * 직전 위치는 JSON 이 아니라 구분자로 붙인다.
     *
     * <p>노선마다 수십 대, 회차마다 통째로 다시 쓰이는 값이라 파싱 비용이 그대로 수집 주기에
     * 얹힌다. 필드가 여섯 개로 고정이라 구분자로 충분하다.
     */
    private String serialize(SubwayTrainObservation o) {
        return String.join(SEP,
                nullToEmpty(o.stationId()), nullToEmpty(o.stationName()), o.status().name(),
                String.valueOf(o.receivedAt().getEpochSecond()),
                nullToEmpty(o.terminalId()), o.express().name());
    }

    private Optional<SubwayTrainObservation> parseObservation(String raw) {
        String[] parts = raw.split("\\" + SEP, -1);
        if (parts.length < 6) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SubwayTrainObservation(
                    emptyToNull(parts[0]), emptyToNull(parts[1]),
                    SubwayTrainStatus.valueOf(parts[2]),
                    Instant.ofEpochSecond(Long.parseLong(parts[3])),
                    emptyToNull(parts[4]), SubwayExpressType.valueOf(parts[5])));
        } catch (Exception e) {
            log.warn("[TrainLog] 직전 위치 파싱 실패 — {}", raw);
            return Optional.empty();
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("열차 사건 직렬화 실패", e);
        }
    }

    private <T> T parse(String raw, Class<T> type) {
        if (raw == null) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, type);
        } catch (Exception e) {
            log.warn("[TrainLog] 사건 파싱 실패 — {}", raw);
            return null;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * 역 기준 출발 색인의 한 줄.
     *
     * <p>{@code terminalId}·{@code express} 를 사건에 <b>박아 둔다</b>. 판정 시점에 현재 위치를
     * 다시 읽으면 그 열차는 이미 사라졌거나 종착·급행 정보가 바뀌어 있을 수 있다.
     */
    record DepartureRow(String trainNo, long from, long to, String quality,
                        String stationName, String terminalId, String express) {

        static DepartureRow of(String trainNo, SubwayTrainEvent e) {
            return new DepartureRow(trainNo, e.from().getEpochSecond(), e.to().getEpochSecond(),
                    e.quality().name(), e.stationName(), e.terminalId(), e.express().name());
        }

        SubwayTrainEvent.Quality qualityEnum() {
            try {
                return SubwayTrainEvent.Quality.valueOf(quality);
            } catch (Exception e) {
                return null;
            }
        }

        SubwayTrainEvent toEvent(SubwayTrainEvent.Type type, String stationId) {
            return new SubwayTrainEvent(type, stationId, stationName,
                    Instant.ofEpochSecond(from), Instant.ofEpochSecond(to),
                    SubwayTrainEvent.Quality.valueOf(quality), terminalId,
                    SubwayExpressType.valueOf(express));
        }
    }

    /** 열차 기준 도착 색인의 한 줄. */
    record ArrivalRow(String stationId, String stationName, long from, long to,
                      String terminalId, String express) {

        static ArrivalRow of(SubwayTrainEvent e) {
            return new ArrivalRow(e.stationId(), e.stationName(),
                    e.from().getEpochSecond(), e.to().getEpochSecond(),
                    e.terminalId(), e.express().name());
        }

        SubwayTrainEvent toEvent() {
            return new SubwayTrainEvent(SubwayTrainEvent.Type.ARRIVED, stationId, stationName,
                    Instant.ofEpochSecond(from), Instant.ofEpochSecond(to),
                    SubwayTrainEvent.Quality.CORROBORATED, terminalId,
                    SubwayExpressType.valueOf(express));
        }
    }
}
