package watoo.grd.nextroute.application.arrival.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.PositionSource;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.TrainStatus;
import watoo.grd.nextroute.application.subway.config.SubwayPositionProperties;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalCode;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.application.subway.service.ServiceDateResolver;
import watoo.grd.nextroute.application.subway.service.SubwayLineOrder;
import watoo.grd.nextroute.application.subway.service.SubwayLineStationIndex;
import watoo.grd.nextroute.application.subway.service.SubwayPatternStationMatcher;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader.Arrival;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 역 도착 응답의 열차마다 "지금 어느 역, 어떤 상태"를 정한다(#317).
 *
 * <pre>
 * 1. 위치 API 직전 관측(subway:pos)이 신선하면 그것     ← POSITION
 *    - 전역출발(3)은 그 역의 바로 전역을 떠난 것이다 → (전역, DEPARTED). 전역을 모르면 2 로
 * 2. 아니면 도착 API 행의 코드·직전 역·현재 역 이름     ← ARRIVAL
 * 3. 둘 다 없으면 모름
 * </pre>
 *
 * <p><b>반대 방향 열차를 가려낸다.</b> 회차 직후 열차가 아직 이전 방향 도착 행에 남는다(수원 하행 목록의 "병점행 · 진위").
 * 위치 API 가 반대 방향으로 더 최근에 봤으면 그 열차는 이 역에 이 방향으로 오지 않는다.
 *
 * <p>위치 조회가 실패해도 목록은 그대로 둔다 — 위치는 덧붙이는 값이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayTrainPositionResolver {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter RECEIVED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SubwayTrainLogPort trainLogPort;
    private final SubwayLineOrder lineOrder;
    private final SubwayLineStationIndex stationIndex;
    private final SubwayPositionProperties positionProperties;
    private final ServiceDateResolver serviceDateResolver;

    /**
     * @param position 모르면 null
     * @param reversed 위치 API 가 반대 방향으로 더 최근에 본 열차 — 목록에서 뺀다
     */
    public record Placed(Arrival arrival, Position position, boolean reversed) {
    }

    /** @param stationId 이름만 알면 null */
    public record Position(String stationId, String stationName, TrainStatus status, Instant observedAt,
                           PositionSource source) {
    }

    public List<Placed> place(String lineId, List<Arrival> arrivals, Instant now) {
        Map<TrainIdentity, SubwayTrainObservation> seen = lastSeen(lineId, arrivals, now);
        Names names = new Names(lineId);
        List<Placed> out = new ArrayList<>(arrivals.size());
        for (Arrival arrival : arrivals) {
            SubwayRealtimeTrain train = arrival.train();
            SubwayTrainObservation own = latest(seen, train.getDirection(), train.getTrainNo());
            SubwayTrainObservation opposite = latest(seen, opposite(lineId, train.getDirection()), train.getTrainNo());
            boolean ownFresh = isFresh(own, now);
            boolean reversed = isFresh(opposite, now)
                    && (own == null || own.receivedAt().isBefore(opposite.receivedAt()));
            if (reversed) {
                log.debug("반대 방향 열차 제외 line={} direction={} trainNo={} 위치={}",
                        lineId, train.getDirection(), train.getTrainNo(), opposite.stationName());
            }
            Position position = ownFresh ? fromPosition(lineId, train.getDirection(), own, names) : null;
            if (position == null) {
                position = fromArrivalRow(train, names);
            }
            out.add(new Placed(arrival, position, reversed));
        }
        return out;
    }

    private Map<TrainIdentity, SubwayTrainObservation> lastSeen(String lineId, List<Arrival> arrivals, Instant now) {
        Set<LocalDate> dates = new LinkedHashSet<>(List.of(serviceDateResolver.resolve(now),
                serviceDateResolver.resolve(now.minus(positionProperties.getFreshnessWindow()))));
        List<TrainIdentity> identities = new ArrayList<>();
        for (Arrival arrival : arrivals) {
            SubwayRealtimeTrain train = arrival.train();
            if (train.getTrainNo() == null || train.getDirection() == null) {
                continue;
            }
            for (LocalDate date : dates) {
                identities.add(new TrainIdentity(date, lineId, train.getDirection(), train.getTrainNo()));
                String opposite = opposite(lineId, train.getDirection());
                if (opposite != null) {
                    identities.add(new TrainIdentity(date, lineId, opposite, train.getTrainNo()));
                }
            }
        }
        if (identities.isEmpty()) {
            return Map.of();
        }
        try {
            return trainLogPort.lastSeenAll(identities);
        } catch (RuntimeException e) {
            log.warn("지하철 열차 위치 조회 실패 — 도착 행으로만 위치를 정한다 line={}: {}", lineId, e.getMessage());
            return Map.of();
        }
    }

    /** 운행일이 둘(04:00 경계)일 수 있다 — 더 최근 관측. */
    private static SubwayTrainObservation latest(Map<TrainIdentity, SubwayTrainObservation> seen,
                                                 String direction, String trainNo) {
        if (direction == null || trainNo == null) {
            return null;
        }
        SubwayTrainObservation best = null;
        for (Map.Entry<TrainIdentity, SubwayTrainObservation> entry : seen.entrySet()) {
            TrainIdentity id = entry.getKey();
            SubwayTrainObservation obs = entry.getValue();
            if (direction.equals(id.direction()) && trainNo.equals(id.trainNo()) && obs.receivedAt() != null
                    && (best == null || obs.receivedAt().isAfter(best.receivedAt()))) {
                best = obs;
            }
        }
        return best;
    }

    /**
     * 신선도 기준은 위치 트래커와 같은 값이다. {@code receivedAt} 은 마지막으로 본 시각이 아니라 원천 시각이 <b>바뀐</b>
     * 마지막 시각이라(정차가 길면 그대로다) 너무 짧게 잡으면 살아 있는 열차를 놓친다.
     */
    private boolean isFresh(SubwayTrainObservation obs, Instant now) {
        if (obs == null || obs.receivedAt() == null) {
            return false;
        }
        return !obs.receivedAt().isBefore(now.minus(positionProperties.getFreshnessWindow()));
    }

    /** 2호선 내·외선, 나머지 상·하행. 방향 표기가 그 둘이 아니면(지선 등) 반대를 모른다. */
    static String opposite(String lineId, String direction) {
        List<String> pair = SubwayStationArrivalQueryService.directionsOf(lineId);
        if (direction == null || pair.size() != 2 || !pair.contains(direction)) {
            return null;
        }
        return pair.get(0).equals(direction) ? pair.get(1) : pair.get(0);
    }

    private Position fromPosition(String lineId, String direction, SubwayTrainObservation obs, Names names) {
        Instant at = obs.receivedAt();
        return switch (obs.status()) {
            case ENTERING -> new Position(obs.stationId(), obs.stationName(), TrainStatus.APPROACHING, at,
                    PositionSource.POSITION);
            case ARRIVED -> new Position(obs.stationId(), obs.stationName(), TrainStatus.ARRIVED, at,
                    PositionSource.POSITION);
            case DEPARTED -> new Position(obs.stationId(), obs.stationName(), TrainStatus.DEPARTED, at,
                    PositionSource.POSITION);
            // 전역출발: 그 행 역의 바로 전역을 떠났다. 전역을 모르면(캐시 없음·갈래 합류) 도착 행으로 넘긴다.
            case LEFT_PREVIOUS -> lineOrder.prevOf(lineId, direction, obs.stationId())
                    .map(prev -> new Position(prev, names.nameOf(prev), TrainStatus.DEPARTED, at,
                            PositionSource.POSITION))
                    .orElse(null);
            case UNKNOWN -> null;
        };
    }

    /**
     * 도착 API 행은 조회역 기준이다 — 0·1 은 조회역, 3·4·5 는 직전 역({@code statnFid}), 99 는 현재 역 이름({@code arvlMsg3}).
     * 2(조회역 출발)는 reader 가 이미 뺐다.
     */
    static Position fromArrivalRow(SubwayRealtimeTrain train, Names names) {
        SubwayArrivalCode code = SubwayArrivalCode.from(train.getArrivalCode());
        Instant at = parse(train.getReceivedAt());
        return switch (code) {
            case ENTERING -> here(train, TrainStatus.APPROACHING, at);
            case ARRIVED -> here(train, TrainStatus.ARRIVED, at);
            case PREV_DEPARTED -> previous(train, TrainStatus.DEPARTED, at, names);
            case PREV_ENTERING -> previous(train, TrainStatus.APPROACHING, at, names);
            case PREV_ARRIVED -> previous(train, TrainStatus.ARRIVED, at, names);
            case ENROUTE -> {
                String name = blankToNull(train.getCurrentStationName());
                yield name == null ? null : new Position(names.idOf(name), name, null, at, PositionSource.ARRIVAL);
            }
            case DEPARTED, UNKNOWN -> null;
        };
    }

    private static Position here(SubwayRealtimeTrain train, TrainStatus status, Instant at) {
        return new Position(train.getStationId(), train.getStationName(), status, at, PositionSource.ARRIVAL);
    }

    private static Position previous(SubwayRealtimeTrain train, TrainStatus status, Instant at, Names names) {
        String id = blankToNull(train.getPrevStationId());
        String name = blankToNull(train.getPrevStationName());
        if (name == null && id != null) {
            name = names.nameOf(id);
        }
        if (id == null && name == null) {
            return null;
        }
        return new Position(id, name, status, at, PositionSource.ARRIVAL);
    }

    private static Instant parse(String receivedAt) {
        if (receivedAt == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(receivedAt, RECEIVED_AT).atZone(SEOUL).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 한 노선의 역 ID ↔ 이름. 역 마스터 캐시({@link SubwayLineStationIndex})에서 한 번 뽑는다 — DB 를 부르지 않는다. */
    class Names {
        private final String lineId;
        private Map<String, String> nameById;
        private Map<String, String> idByName;

        Names(String lineId) {
            this.lineId = lineId;
        }

        String nameOf(String stationId) {
            load();
            return nameById.get(stationId);
        }

        /** 같은 노선에 같은 이름이 둘이면 모름. */
        String idOf(String stationName) {
            load();
            return idByName.get(stationName);
        }

        private void load() {
            if (nameById != null) {
                return;
            }
            nameById = new HashMap<>();
            idByName = new HashMap<>();
            Set<String> duplicated = new LinkedHashSet<>();
            for (SubwayPatternStationMatcher.Candidate c : stationIndex.candidates(lineId)) {
                nameById.put(c.stationId(), c.stationName());
                if (idByName.putIfAbsent(c.stationName(), c.stationId()) != null) {
                    duplicated.add(c.stationName());
                }
            }
            duplicated.forEach(idByName::remove);
        }
    }
}
