package watoo.grd.nextroute.application.arrival.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.Departed;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.DepartureSource;
import watoo.grd.nextroute.application.subway.service.SubwayDepartureLookup;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.Segment;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.Direction;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.Train;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayLineStripUseCase;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayStationArrivalsUseCase;
import watoo.grd.nextroute.application.arrival.service.SubwayTrainPositionResolver.Placed;
import watoo.grd.nextroute.application.arrival.service.SubwayTrainPositionResolver.Position;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalCode;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader.Arrival;
import watoo.grd.nextroute.application.subway.service.SubwayStationsAway;
import watoo.grd.nextroute.application.subway.service.SubwayTrainKindJudge;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.SubwayErrorCode;

import java.text.Collator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final SubwayDepartureLookup departureLookup;
    private final SubwayTrainPositionResolver positionResolver;
    private final SubwayTrainSegmentResolver segmentResolver;
    private final GetSubwayLineStripUseCase lineStrip;
    private final Clock clock;
    private final SubwayTrainKindJudge kindJudge;

    @Override
    public SubwayStationArrivalsResponse getArrivals(String stationId) {
        SubwayStation station = subwayStationRepository.findByStationId(stationId)
                .orElseThrow(() -> new BusinessException(SubwayErrorCode.SUBWAY_STATION_NOT_FOUND));

        List<Arrival> arrivals = arrivalReader.arrivals(station.getLineId(), station.getStationId(), null);
        List<Placed> placed = positionResolver.place(station.getLineId(), arrivals, clock.instant()).stream()
                .filter(p -> !p.reversed())
                .filter(p -> !passed(p, station))
                .filter(p -> !terminatesBefore(p, station))
                .map(p -> p.withSegment(segment(p, station)))
                .toList();
        Map<String, List<Departed>> departed = departed(station.getLineId(), station.getStationId(), upcoming(placed));
        List<Direction> directions = groupPlaced(placed, departed, p -> toTrain(p, kindOf(p, station))).stream()
                .map(direction -> withStrip(direction, station))
                .toList();
        return new SubwayStationArrivalsResponse(station.getStationId(), station.getLineId(), directions);
    }

    /**
     * 열차 종류·이 역 정차(subway-express PR-S3). 종류 근거는 같은 snapshot 의 그 열차 모든 행이다(reader 가 모은다).
     * 판정 실패는 "모름"(null) — 목록을 깨뜨리지 않는다.
     */
    private SubwayTrainKindJudge.Judgement kindOf(Placed placed, SubwayStation station) {
        return kindOf(placed, station.getStationId(), station.getLineId());
    }

    private SubwayTrainKindJudge.Judgement kindOf(Placed placed, String stationId, String lineId) {
        try {
            SubwayRealtimeTrain train = placed.arrival().train();
            return kindJudge.judge(new SubwayTrainKindJudge.Train(lineId, train.getDirection(),
                            train.getTrainNo(), train.getDestinationName(), placed.arrival().sightings()),
                    stationId, null);
        } catch (RuntimeException e) {
            log.debug("열차 종류 판정 실패 station={}: {}", stationId, e.getMessage());
            return null;
        }
    }

    private SubwayTrainKindJudge.Judgement kindOf(SubwayDepartureLookup.Departure row, String lineId,
                                                  String direction, String stationId) {
        try {
            return kindJudge.judge(new SubwayTrainKindJudge.Train(lineId, direction, row.trainNo(),
                    row.destinationOrTerminal(), List.of(row.sighting())), stationId, null);
        } catch (RuntimeException e) {
            log.debug("떠난 열차 종류 판정 실패 station={}: {}", stationId, e.getMessage());
            return null;
        }
    }

    /** 이 역을 이미 지난 열차(#317 PR-3). 판정 실패는 "안 지났다" — 목록에서 열차를 잘못 빼는 쪽이 더 나쁘다. */
    private boolean passed(Placed placed, SubwayStation station) {
        try {
            SubwayRealtimeTrain train = placed.arrival().train();
            return segmentResolver.hasPassed(station.getLineId(), train.getDirection(), station.getStationId(),
                    placed.position(), SubwayTrainPositionResolver.parse(train.getReceivedAt()));
        } catch (RuntimeException e) {
            log.debug("지난 열차 판정 실패 station={}: {}", station.getStationId(), e.getMessage());
            return false;
        }
    }

    /** 이 역에 오기 전에 운행을 끝내는 열차. 판정 실패는 "온다" — 지난 열차 판정과 같은 이유. */
    private boolean terminatesBefore(Placed placed, SubwayStation station) {
        try {
            SubwayRealtimeTrain train = placed.arrival().train();
            return segmentResolver.terminatesBefore(station.getLineId(), train.getDirection(), station.getStationId(),
                    train.getDestinationName(), placed.position());
        } catch (RuntimeException e) {
            log.debug("종착 판정 실패 station={}: {}", station.getStationId(), e.getMessage());
            return false;
        }
    }

    private Segment segment(Placed placed, SubwayStation station) {
        try {
            SubwayRealtimeTrain train = placed.arrival().train();
            return segmentResolver.segment(station.getLineId(), train.getDirection(), train.getTrainNo(),
                    placed.position());
        } catch (RuntimeException e) {
            log.debug("구간 앵커 계산 실패 station={}: {}", station.getStationId(), e.getMessage());
            return null;
        }
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
     * 방향별로 이 역을 떠난 열차(#299). 원천은 {@link SubwayDepartureLookup} 이 먼저다 — 열차 선택과 같은 창구라 여기 보인
     * 열차는 반드시 고를 수 있다(#317). 위치 API 가 없는 노선은 도착 API 기록으로 채운다.
     *
     * <p><b>출발 기록 전 틈(#645)</b>: 도착 API 가 "조회역 출발"(코드 2)을 내면 reader 는 그 열차를 곧 오는 목록에서 뺀다.
     * 그런데 위치 출발 기록은 위치 API 가 다음 역을 보여 줘야 생겨 약 1분 늦다. 그사이 열차가 응답 어디에도 없어 노선 그림에서
     * 사라졌다. 그래서 캐시의 막 떠난 열차({@link SubwayRealtimeArrivalReader#justDeparted}) 중 출발 기록이 아직 없는 열차를
     * 원천 ARRIVAL 로 넣는다. 열차 선택도 같은 캐시를 먼저 보므로(SubwayTrainSelectionService#departedCandidates) 여기 보인
     * 열차는 고를 수 있다. 출발 기록이 들어오면 그 값이 이긴다.
     *
     * <p><b>도착 원천 떠난 열차는 아직 곧 오는 목록에 있으면 뺀다.</b> 도착 API 행이 한 회차 빠졌다 돌아오면 기록기가
     * "사라짐 = 출발"로 남긴다 — 그 열차가 지금 오는 중으로 보이는데 떠난 쪽에도 있으면 화면이 두 번 그린다. 위치 원천은
     * 관측된 출발이라 그대로 둔다.
     *
     * <p>못 읽으면 빈 목록이다. 곧 오는 열차 응답까지 깨뜨릴 이유가 없다.
     */
    private Map<String, List<Departed>> departed(String lineId, String stationId, Map<String, Set<String>> upcoming) {
        Instant now = clock.instant();
        Instant from = now.minus(DEPARTED_WINDOW);
        Map<String, String> stationNames = new HashMap<>();
        Map<String, List<Arrival>> justDeparted = justDeparted(lineId, stationId, from);
        Map<String, List<Departed>> byDirection = new LinkedHashMap<>();
        try {
            for (String direction : directionsOf(lineId)) {
                List<SubwayDepartureLookup.Departure> recorded = departureLookup.find(lineId, direction, stationId, from, now);
                Set<String> recordedTrainNos = new HashSet<>();
                recorded.forEach(d -> recordedTrainNos.add(d.trainNo()));
                List<Departed> rows = new ArrayList<>();
                for (Arrival arrival : justDeparted.getOrDefault(direction, List.of())) {
                    if (!recordedTrainNos.contains(arrival.train().getTrainNo())) {
                        rows.add(toDeparted(arrival, departedAtOf(arrival, now),
                                kindOf(new Placed(arrival, null, false), stationId, lineId)));
                    }
                }
                Set<String> coming = upcoming.getOrDefault(direction, Set.of());
                recorded.stream()
                        .filter(d -> d.source() != SubwayDepartureLookup.Source.ARRIVAL || !coming.contains(d.trainNo()))
                        .limit(DEPARTED_LIMIT)
                        .map(d -> toDeparted(d, lineId, direction, stationNames, kindOf(d, lineId, direction, stationId)))
                        .forEach(rows::add);
                List<Departed> limited = rows.stream()
                        .filter(d -> d.source() != DepartureSource.ARRIVAL || !coming.contains(d.trainNo()))
                        .sorted(Comparator.comparing(Departed::departedAt).reversed())
                        .limit(DEPARTED_LIMIT)
                        .toList();
                if (!limited.isEmpty()) {
                    byDirection.put(direction, limited);
                }
            }
        } catch (RuntimeException e) {
            log.warn("지하철 떠난 열차 조회 실패 — 빈 목록으로 응답 line={} station={}: {}", lineId, stationId, e.getMessage());
            return Map.of();
        }
        return byDirection;
    }

    /** 곧 오는 목록의 열차 번호, 방향별. */
    private static Map<String, Set<String>> upcoming(List<Placed> placed) {
        Map<String, Set<String>> byDirection = new HashMap<>();
        for (Placed p : placed) {
            SubwayRealtimeTrain train = p.arrival().train();
            String direction = directionOf(train);
            if (direction != null && train.getTrainNo() != null) {
                byDirection.computeIfAbsent(direction, key -> new HashSet<>()).add(train.getTrainNo());
            }
        }
        return byDirection;
    }

    /** 캐시의 "조회역 출발" 행, 방향별. 창보다 오래된 행은 뺀다. 실패하면 빈 값 — 출발 기록만으로 답한다. */
    private Map<String, List<Arrival>> justDeparted(String lineId, String stationId, Instant from) {
        Map<String, List<Arrival>> byDirection = new HashMap<>();
        try {
            for (Arrival arrival : arrivalReader.justDeparted(lineId, stationId, null)) {
                SubwayRealtimeTrain train = arrival.train();
                String direction = directionOf(train);
                Instant receivedAt = SubwayTrainPositionResolver.parse(train.getReceivedAt());
                if (direction == null || train.getTrainNo() == null
                        || (receivedAt != null && receivedAt.isBefore(from))) {
                    continue;
                }
                byDirection.computeIfAbsent(direction, key -> new ArrayList<>()).add(arrival);
            }
        } catch (RuntimeException e) {
            log.warn("지하철 막 떠난 열차 캐시 조회 실패 — 출발 기록만으로 응답 line={} station={}: {}",
                    lineId, stationId, e.getMessage());
            return Map.of();
        }
        return byDirection;
    }

    /** 코드 2 행의 원천 시각({@code recptnDt}). 모르면 지금 — 방금 떠난 열차라 오차가 수집 주기 안이다. */
    private static Instant departedAtOf(Arrival arrival, Instant now) {
        Instant receivedAt = SubwayTrainPositionResolver.parse(arrival.train().getReceivedAt());
        return receivedAt == null || receivedAt.isAfter(now) ? now : receivedAt;
    }

    private static Departed toDeparted(Arrival arrival, Instant departedAt, SubwayTrainKindJudge.Judgement kind) {
        SubwayRealtimeTrain train = arrival.train();
        return new Departed(
                train.getTrainNo(),
                train.getDestinationName(),
                train.getTrainType(),
                OffsetDateTime.ofInstant(departedAt, SEOUL),
                null,
                DepartureSource.ARRIVAL,
                kind == null ? null : kind.kind(),
                kind == null ? null : kind.label(),
                kind == null ? null : kind.stopsAtBoarding());
    }

    private Departed toDeparted(SubwayDepartureLookup.Departure row, String lineId, String direction,
                                Map<String, String> stationNames, SubwayTrainKindJudge.Judgement kind) {
        TrainIdentity identity = new TrainIdentity(row.serviceDate(), lineId, direction, row.trainNo());
        String current = trainLogPort.lastSeen(identity)
                .filter(seen -> seen.receivedAt() != null && !seen.receivedAt().isBefore(row.observedFrom()))
                .map(SubwayTrainObservation::stationName)
                .orElse(null);
        boolean position = row.source() == SubwayDepartureLookup.Source.POSITION;
        return new Departed(
                row.trainNo(),
                position ? stationName(row.terminalId(), stationNames) : row.destinationName(),
                row.trainTypeLabel(),
                OffsetDateTime.ofInstant(row.departedAt(), SEOUL),
                current,
                position ? DepartureSource.POSITION : DepartureSource.ARRIVAL,
                kind == null ? null : kind.kind(),
                kind == null ? null : kind.label(),
                kind == null ? null : kind.stopsAtBoarding());
    }

    private String stationName(String stationId, Map<String, String> cache) {
        if (stationId == null || stationId.isBlank()) {
            return null;
        }
        return cache.computeIfAbsent(stationId, id -> subwayStationRepository.findByStationId(id)
                .map(SubwayStation::getStationName)
                .orElse(null));
    }

    /**
     * 도착 응답의 {@code trainType} 표기(일반·급행·특급)에 맞춘다. 모르면 null. 9(ITX)는 enum 이 접어 버리므로 원문이 필요하다 —
     * {@link SubwayDepartureLookup.Departure#trainTypeLabel}.
     */
    static String trainType(SubwayExpressType express) {
        return SubwayDepartureLookup.Departure.trainTypeLabel(express, null);
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
        return groupPlaced(arrivals, departed, placed -> toTrain(placed, null));
    }

    static List<Direction> groupPlaced(List<Placed> arrivals, Map<String, List<Departed>> departed,
                                       java.util.function.Function<Placed, Train> toTrain) {
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
                            byDirection.get(direction).stream().map(toTrain).toList(),
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

    private static Train toTrain(Placed placed, SubwayTrainKindJudge.Judgement kind) {
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
                position == null ? null : position.source(),
                placed.segment(),
                kind == null ? null : kind.kind(),
                kind == null ? null : kind.label(),
                kind == null ? null : kind.stopsAtBoarding());
    }
}
