package watoo.grd.nextroute.application.arrival.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.Direction;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.Train;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayStationArrivalsUseCase;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalCode;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader.Arrival;
import watoo.grd.nextroute.application.subway.service.SubwayStationsAway;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.SubwayErrorCode;

import java.text.Collator;
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
@Service
@RequiredArgsConstructor
public class SubwayStationArrivalQueryService implements GetSubwayStationArrivalsUseCase {

    static final String UNKNOWN_DIRECTION_LABEL = "방향 정보 없음";

    private static final String DESTINATION_SUFFIX = " 방면";

    private final SubwayRealtimeArrivalReader arrivalReader;
    private final SubwayStationRepository subwayStationRepository;

    @Override
    public SubwayStationArrivalsResponse getArrivals(String stationId) {
        SubwayStation station = subwayStationRepository.findByStationId(stationId)
                .orElseThrow(() -> new BusinessException(SubwayErrorCode.SUBWAY_STATION_NOT_FOUND));

        List<Arrival> arrivals = arrivalReader.arrivals(station.getLineId(), station.getStationId(), null);
        return new SubwayStationArrivalsResponse(
                station.getStationId(), station.getLineId(), group(arrivals));
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
        Map<String, List<Arrival>> byDirection = new LinkedHashMap<>();
        for (Arrival arrival : arrivals) {
            byDirection.computeIfAbsent(directionOf(arrival.train()), key -> new ArrayList<>()).add(arrival);
        }

        Collator korean = Collator.getInstance(Locale.KOREAN);
        List<String> order = new ArrayList<>(byDirection.keySet());
        order.sort(Comparator.nullsLast(korean::compare));

        Map<String, String> labels = new LinkedHashMap<>();
        order.forEach(direction -> labels.put(direction, label(direction, byDirection.get(direction))));
        Map<String, Long> labelCounts = new HashMap<>();
        labels.values().forEach(label -> labelCounts.merge(label, 1L, Long::sum));

        return order.stream()
                .map(direction -> {
                    String label = labels.get(direction);
                    if (direction != null && labelCounts.get(label) > 1) {
                        label = direction;
                    }
                    return new Direction(direction, label,
                            byDirection.get(direction).stream().map(SubwayStationArrivalQueryService::toTrain).toList());
                })
                .toList();
    }

    private static String directionOf(SubwayRealtimeTrain train) {
        String direction = train.getDirection();
        return direction == null || direction.isBlank() ? null : direction.trim();
    }

    /**
     * 행선지 최빈값 + " 방면". 동률이면 먼저 오는 열차의 행선지다(입력이 도착 순이라 첫 등장 순서가 곧
     * 도착 순서). 행선지가 하나도 없으면 방향 원문.
     */
    private static String label(String direction, List<Arrival> arrivals) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Arrival arrival : arrivals) {
            String destination = arrival.train().getDestinationName();
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

    private static Train toTrain(Arrival arrival) {
        SubwayRealtimeTrain train = arrival.train();
        return new Train(
                train.getTrainNo(),
                train.getDestinationName(),
                train.getTrainType(),
                arrival.arrivalSeconds(),
                SubwayStationsAway.resolve(
                        SubwayArrivalCode.from(train.getArrivalCode()), train.getCurrentMessage()),
                train.getCurrentMessage());
    }
}
