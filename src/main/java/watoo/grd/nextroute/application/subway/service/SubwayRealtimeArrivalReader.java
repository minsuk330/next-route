package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 실시간 지하철 snapshot 에서 특정 노선·역·방향의 열차를 읽는다.
 *
 * <p>같은 규칙을 쓰는 곳이 둘 이상이라 여기로 모았다. 방향 문자열 변환과 경과시간 보정을 각자
 * 들고 있으면 한쪽만 고쳐지는 순간부터 두 화면이 다른 도착시간을 말한다.
 *
 * <p>소스는 두 가지다. 수집기가 채워 둔 캐시({@link #arrivals})와 역별 실시간 호출
 * ({@link #arrivalsByStation}). 고르는 규칙은 하나로 공유하고 소스만 다르다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayRealtimeArrivalReader {

    private static final DateTimeFormatter RECEIVED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SubwayRealtimeCachePort cachePort;
    private final SubwayApiPort subwayApiPort;

    /**
     * 수집기가 채운 캐시 snapshot 에서 읽는다.
     *
     * <p>전체 역을 한 번에 수집해 두는 방식이라 조회는 공짜다. 다만 수집기가 꺼져 있으면 항상
     * 비어 있으므로, 그 상태에서도 답이 필요한 곳은 {@link #arrivalsByStation} 을 쓴다.
     *
     * @param wayCode ODsay 방향 코드. null 이면 방향을 가리지 않는다
     */
    public List<Arrival> arrivals(String lineId, String stationId, Integer wayCode) {
        Optional<SubwayRealtimeSnapshot> snapshot = cachePort.readSnapshot();
        if (snapshot.isEmpty() || snapshot.get().getTrains() == null) {
            return List.of();
        }
        return select(snapshot.get().getTrains(), lineId, stationId, wayCode);
    }

    /**
     * 역 하나를 실시간 API 로 직접 물어본다.
     *
     * <p>수집기가 꺼져 있어 캐시가 비는 환경을 위한 경로다. 전체 수집과 달리 <b>필요한 역만</b>
     * 부르므로 호출량이 조회하는 역 수에 비례한다.
     *
     * <p>호출이 실패하면 빈 목록이다. 여기서 정적 대기시간을 만들어 내지 않는다 — 부르는 쪽이
     * "모름"으로 내려보낸다.
     *
     * @param stationName API 가 역 이름으로만 조회를 받는다
     */
    public List<Arrival> arrivalsByStation(String lineId, String stationId,
                                           String stationName, Integer wayCode) {
        if (stationName == null || stationName.isBlank()) {
            return List.of();
        }
        List<SubwayArrivalInfo> fetched;
        try {
            fetched = subwayApiPort.getRealtimeArrivalByStation(stationName);
        } catch (Exception e) {
            log.warn("지하철 실시간 역별 조회 실패 — station={}, {}", stationName, e.getMessage());
            return List.of();
        }
        if (fetched == null || fetched.isEmpty()) {
            return List.of();
        }
        return select(fetched.stream().map(this::toTrain).toList(), lineId, stationId, wayCode);
    }

    /**
     * 노선·역·방향이 맞는 열차를 도착이 빠른 순으로 고른다. 이미 지나간 열차는 빼고 중복도 지운다.
     *
     * <p>캐시에서 왔든 API 에서 왔든 이 규칙은 같아야 한다. 소스마다 따로 두면 한쪽만 고쳐지는
     * 순간부터 두 경로가 다른 열차를 고른다.
     */
    private List<Arrival> select(List<SubwayRealtimeTrain> trains,
                                 String lineId, String stationId, Integer wayCode) {
        LocalDateTime now = LocalDateTime.now(SEOUL);
        String direction = toDirection(lineId, wayCode);

        Map<String, SubwayRealtimeTrain> deduped = new LinkedHashMap<>();
        trains.stream()
                .filter(train -> lineId != null && lineId.equals(train.getLineId()))
                .filter(train -> stationId != null && stationId.equals(train.getStationId()))
                .filter(train -> direction == null || direction.equals(train.getDirection()))
                .filter(train -> {
                    Integer adjusted = adjustedSeconds(train, now);
                    return adjusted == null || adjusted >= 0;
                })
                .forEach(train ->
                        deduped.putIfAbsent(train.getDirection() + ":" + train.getTrainNo(), train));

        return deduped.values().stream()
                .sorted(Comparator.comparingInt(train ->
                        Optional.ofNullable(adjustedSeconds(train, now)).orElse(Integer.MAX_VALUE)))
                .map(train -> new Arrival(train, adjustedSeconds(train, now)))
                .toList();
    }

    /**
     * 실시간 API 응답을 캐시와 같은 모양으로 바꾼다.
     *
     * <p>이웃 역 이름과 구간 소요시간은 채우지 않는다. 그 값들은 수집기가 도착 보간에 쓰는
     * 것이고, 여기서 필요한 것은 노선·역·방향·남은 초뿐이다.
     */
    private SubwayRealtimeTrain toTrain(SubwayArrivalInfo info) {
        return SubwayRealtimeTrain.builder()
                .trainNo(info.trainNo())
                .stationId(info.stationId())
                .lineId(info.lineId())
                .direction(info.direction())
                .stationName(info.stationName())
                .arrivalSeconds(info.arrivalSeconds())
                .receivedAt(info.receivedAt())
                .arrivalCode(info.arrivalCode())
                .currentMessage(info.currentMessage())
                .destinationName(info.destinationName())
                .trainType(info.trainType())
                .build();
    }

    /**
     * ODsay {@code wayCode} → 서울 실시간 API 의 방향 문자열.
     *
     * <p>2호선은 순환선이라 상·하행이 아니라 내·외선이다.
     */
    public String toDirection(String lineId, Integer wayCode) {
        if (wayCode == null) {
            return null;
        }
        if ("1002".equals(lineId)) {
            return wayCode == 0 ? "내선" : "외선";
        }
        return wayCode == 1 ? "상행" : "하행";
    }

    /**
     * 남은 도착 초를 지금 기준으로 보정한다.
     *
     * <p>수집 시점의 "3분 후 도착"을 그대로 쓰면 수집이 1분 전이었을 때 1분을 더 기다리게 된다.
     * 파싱에 실패하면 원값을 쓴다 — 보정 실패가 조회 실패로 번지지 않게 한다.
     */
    public Integer adjustedSeconds(SubwayRealtimeTrain train, LocalDateTime now) {
        Integer raw = train.getArrivalSeconds();
        if (raw == null) {
            return null;
        }
        if (train.getReceivedAt() == null) {
            return raw;
        }
        try {
            LocalDateTime received = LocalDateTime.parse(train.getReceivedAt(), RECEIVED_AT_FORMAT);
            long elapsed = Duration.between(received, now).getSeconds();
            return Math.toIntExact(raw - elapsed);
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * 열차 하나와 보정된 남은 초.
     *
     * @param arrivalSeconds 보정 후 남은 초. 알 수 없으면 null
     */
    public record Arrival(SubwayRealtimeTrain train, Integer arrivalSeconds) {

        public boolean hasSeconds() {
            return arrivalSeconds != null;
        }
    }
}
