package watoo.grd.nextroute.application.arrival.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.Departed;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.Direction;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.Train;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayLineStripUseCase;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayStationArrivalsUseCase;
import watoo.grd.nextroute.application.arrival.service.SubwayTrainPositionResolver.Placed;
import watoo.grd.nextroute.application.arrival.service.SubwayTrainPositionResolver.Position;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalCode;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader.Arrival;
import watoo.grd.nextroute.application.subway.service.ServiceDateResolver;
import watoo.grd.nextroute.application.subway.service.SubwayStationsAway;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.SubwayErrorCode;

import java.text.Collator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 역 ID 로 묻는 지하철 도착(#277).
 *
 * <p>좌표 조회({@link SubwayArrivalQueryService})는 좌표를 역으로 되찾는 과정에서 환승역 노선이
 * 바뀔 수 있다(#276). 역 ID 를 이미 아는 호출자는 그 과정을 거칠 이유가 없다.
 *
 * <p>열차는 좌표 조회와 같은 캐시 reader 에서 읽는다. 지나간 열차를 빼는 규칙과 도착 순서를
 * 두 경로가 공유해야 같은 역이 두 화면에서 다른 열차를 말하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayStationArrivalQueryService implements GetSubwayStationArrivalsUseCase {

    static final String UNKNOWN_DIRECTION_LABEL = "방향 정보 없음";

    private static final String DESTINATION_SUFFIX = " 방면";

    /** 떠난 열차를 보는 창. 앱은 약 10분·3대까지만 보여 준다(#299). */
    static final Duration DEPARTED_WINDOW = Duration.ofMinutes(10);
    static final int DEPARTED_LIMIT = 3;

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SubwayRealtimeArrivalReader arrivalReader;
    private final SubwayStationRepository subwayStationRepository;
    private final SubwayTrainLogPort trainLogPort;
    private final ServiceDateResolver serviceDateResolver;
    private final SubwayTrainPositionResolver positionResolver;
    private final GetSubwayLineStripUseCase lineStrip;
    private final Clock clock;

    @Override
    public SubwayStationArrivalsResponse getArrivals(String stationId) {
        SubwayStation station = subwayStationRepository.findByStationId(stationId)
                .orElseThrow(() -> new BusinessException(SubwayErrorCode.SUBWAY_STATION_NOT_FOUND));

        List<Arrival> arrivals = arrivalReader.arrivals(station.getLineId(), station.getStationId(), null);
        List<Placed> placed = positionResolver.place(station.getLineId(), arrivals, clock.instant()).stream()
                .filter(p -> !p.reversed())
                .toList();
        Map<String, List<Departed>> departed = departed(station.getLineId(), station.getStationId());
        List<Direction> directions = groupPlaced(placed, departed).stream()
                .map(direction -> withStrip(direction, station))
                .toList();
        return new SubwayStationArrivalsResponse(station.getStationId(), station.getLineId(), directions);
    }

    /**
     * 방향별 노선도(#317). 노선도는 덧붙이는 값이다 — 모르거나 실패하면 그 칸만 비우고 열차 목록은 그대로 낸다.
     */
    private Direction withStrip(Direction direction, SubwayStation station) {
        if (direction.direction() == null) {
            return direction;
        }
        try {
            return lineStrip.strip(station.getLineId(), direction.direction(), station.getStationId())
                    .map(direction::withStrip)
                    .orElse(direction);
        } catch (RuntimeException e) {
            log.warn("지하철 노선도 실패 — 비워서 응답 station={} direction={}: {}",
                    station.getStationId(), direction.direction(), e.getMessage());
            return direction;
        }
    }

    /**
     * 방향별로 이 역을 떠난 열차(#299). 원천은 위치 수집기가 남긴 출발 사건이다
     * ({@link SubwayTrainLogPort#findDepartures}) — 탑승 판정과 같은 조회라 {@code trainNo} 가 열차 선택과 맞는다.
     *
     * <p>로그를 못 읽으면 빈 목록이다. 곧 오는 열차 응답까지 깨뜨릴 이유가 없다.
     */
    private Map<String, List<Departed>> departed(String lineId, String stationId) {
        Instant now = clock.instant();
        Instant from = now.minus(DEPARTED_WINDOW);
        Set<LocalDate> serviceDates = new LinkedHashSet<>(List.of(
                serviceDateResolver.resolve(now), serviceDateResolver.resolve(from)));
        Map<String, String> stationNames = new HashMap<>();
        Map<String, List<Departed>> byDirection = new LinkedHashMap<>();
        try {
            for (String direction : directionsOf(lineId)) {
                Map<String, SubwayTrainLogPort.TrainDeparture> latest = new LinkedHashMap<>();
                Map<String, LocalDate> dateOf = new HashMap<>();
                for (LocalDate serviceDate : serviceDates) {
                    for (SubwayTrainLogPort.TrainDeparture row
                            : trainLogPort.findDepartures(serviceDate, lineId, direction, stationId, from, now)) {
                        SubwayTrainLogPort.TrainDeparture kept = latest.get(row.trainNo());
                        if (kept == null || row.event().estimate().isAfter(kept.event().estimate())) {
                            latest.put(row.trainNo(), row);
                            dateOf.put(row.trainNo(), serviceDate);
                        }
                    }
                }
                List<Departed> rows = latest.values().stream()
                        .sorted(Comparator.comparing((SubwayTrainLogPort.TrainDeparture d) -> d.event().estimate())
                                .reversed())
                        .limit(DEPARTED_LIMIT)
                        .map(d -> toDeparted(d, new TrainIdentity(dateOf.get(d.trainNo()), lineId, direction,
                                d.trainNo()), stationNames))
                        .toList();
                if (!rows.isEmpty()) {
                    byDirection.put(direction, rows);
                }
            }
        } catch (RuntimeException e) {
            log.warn("지하철 떠난 열차 조회 실패 — 빈 목록으로 응답 line={} station={}: {}", lineId, stationId, e.getMessage());
            return Map.of();
        }
        return byDirection;
    }

    private Departed toDeparted(SubwayTrainLogPort.TrainDeparture row, TrainIdentity identity,
                                Map<String, String> stationNames) {
        SubwayTrainEvent event = row.event();
        String current = trainLogPort.lastSeen(identity)
                .filter(seen -> seen.receivedAt() != null && !seen.receivedAt().isBefore(event.from()))
                .map(SubwayTrainObservation::stationName)
                .orElse(null);
        return new Departed(
                row.trainNo(),
                stationName(event.terminalId(), stationNames),
                trainType(event.express()),
                OffsetDateTime.ofInstant(event.estimate(), SEOUL),
                current);
    }

    private String stationName(String stationId, Map<String, String> cache) {
        if (stationId == null || stationId.isBlank()) {
            return null;
        }
        return cache.computeIfAbsent(stationId, id -> subwayStationRepository.findByStationId(id)
                .map(SubwayStation::getStationName)
                .orElse(null));
    }

    /** 도착 응답의 {@code trainType} 표기(일반·급행·특급)에 맞춘다. 모르면 null. */
    static String trainType(SubwayExpressType express) {
        if (express == null) {
            return null;
        }
        return switch (express) {
            case NORMAL -> "일반";
            case EXPRESS -> "급행";
            case SPECIAL -> "특급";
            case UNKNOWN -> null;
        };
    }

    /** 순환선(2호선)만 내·외선이다. */
    static List<String> directionsOf(String lineId) {
        return "1002".equals(lineId) ? List.of("내선", "외선") : List.of("상행", "하행");
    }

    /**
     * 방향별로 묶고 표시 이름과 순서를 정한다.
     *
     * <p><b>순서는 방향 이름순으로 고정한다.</b> 도착 순서로 두면 다음 열차가 바뀔 때마다 탭
     * 자리가 바뀐다. 방향 안의 열차는 reader 가 이미 도착 순으로 줬으므로 다시 정렬하지 않는다.
     *
     * <p><b>두 방향 이름이 같으면 방향 원문을 쓴다.</b> 순환선은 양쪽 열차가 같은 종착(2호선 성수)을
     * 향해 행선지로는 구분되지 않는다.
     */
    static List<Direction> group(List<Arrival> arrivals) {
        return group(arrivals, Map.of());
    }

    static List<Direction> group(List<Arrival> arrivals, Map<String, List<Departed>> departed) {
        return groupPlaced(arrivals.stream().map(a -> new Placed(a, null, false)).toList(), departed);
    }

    /**
     * @param departed 방향 → 떠난 열차. 곧 오는 열차가 없는 방향도 떠난 열차가 있으면 나온다 — 막차 직후처럼
     *                 곧 오는 열차가 비어도 방금 탄 사용자는 자기 열차를 찾아야 한다
     */
    static List<Direction> groupPlaced(List<Placed> arrivals, Map<String, List<Departed>> departed) {
        Map<String, List<Placed>> byDirection = new LinkedHashMap<>();
        for (Placed arrival : arrivals) {
            byDirection.computeIfAbsent(directionOf(arrival.arrival().train()), key -> new ArrayList<>()).add(arrival);
        }
        departed.keySet().forEach(direction -> byDirection.computeIfAbsent(direction, key -> new ArrayList<>()));

        Collator korean = Collator.getInstance(Locale.KOREAN);
        List<String> order = new ArrayList<>(byDirection.keySet());
        order.sort(Comparator.nullsLast(korean::compare));

        Map<String, String> labels = new LinkedHashMap<>();
        order.forEach(direction -> labels.put(direction, label(direction, byDirection.get(direction),
                departed.getOrDefault(direction, List.of()))));
        Map<String, Long> labelCounts = new HashMap<>();
        labels.values().forEach(label -> labelCounts.merge(label, 1L, Long::sum));

        return order.stream()
                .map(direction -> {
                    String label = labels.get(direction);
                    if (direction != null && labelCounts.get(label) > 1) {
                        label = direction;
                    }
                    return new Direction(direction, label,
                            byDirection.get(direction).stream().map(SubwayStationArrivalQueryService::toTrain).toList(),
                            departed.getOrDefault(direction, List.of()));
                })
                .toList();
    }

    private static String directionOf(SubwayRealtimeTrain train) {
        String direction = train.getDirection();
        return direction == null || direction.isBlank() ? null : direction.trim();
    }

    /**
     * 행선지 최빈값 + " 방면". 동률이면 먼저 오는 열차의 행선지다(입력이 도착 순이라 첫 등장 순서가 곧
     * 도착 순서). 곧 오는 열차가 없으면 떠난 열차 행선지로 같은 규칙을 쓴다. 행선지가 하나도 없으면 방향 원문.
     */
    private static String label(String direction, List<Placed> arrivals, List<Departed> departed) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> destinations = arrivals.isEmpty()
                ? departed.stream().map(Departed::destinationName).toList()
                : arrivals.stream().map(a -> a.arrival().train().getDestinationName()).toList();
        for (String destination : destinations) {
            if (destination != null && !destination.isBlank()) {
                counts.merge(destination.trim(), 1, Integer::sum);
            }
        }
        String top = null;
        int topCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > topCount) {
                top = entry.getKey();
                topCount = entry.getValue();
            }
        }
        if (top != null) {
            return top + DESTINATION_SUFFIX;
        }
        return direction != null ? direction : UNKNOWN_DIRECTION_LABEL;
    }

    private static Train toTrain(Placed placed) {
        Arrival arrival = placed.arrival();
        SubwayRealtimeTrain train = arrival.train();
        Position position = placed.position();
        return new Train(
                train.getTrainNo(),
                train.getDestinationName(),
                train.getTrainType(),
                arrival.arrivalSeconds(),
                SubwayStationsAway.resolve(
                        SubwayArrivalCode.from(train.getArrivalCode()), train.getCurrentMessage()),
                train.getCurrentMessage(),
                position == null ? null : position.stationId(),
                position == null ? null : position.stationName(),
                position == null ? null : position.status(),
                position == null || position.observedAt() == null
                        ? null : OffsetDateTime.ofInstant(position.observedAt(), SEOUL),
                position == null ? null : position.source());
    }
}
