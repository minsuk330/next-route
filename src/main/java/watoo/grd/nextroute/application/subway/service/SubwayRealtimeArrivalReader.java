package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
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
 * <p>이 컴포넌트는 <b>캐시만</b> 읽는다. 열차 API 를 직접 부르지 않는다 — 수집은
 * {@code RealtimeSubwayCollector} 가 주기적으로 한다.
 */
@Component
@RequiredArgsConstructor
public class SubwayRealtimeArrivalReader {

    private static final DateTimeFormatter RECEIVED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SubwayRealtimeCachePort cachePort;

    /**
     * 도착이 빠른 순으로 정렬된 열차. 이미 지나간 열차는 빼고, 같은 열차 중복도 지운다.
     *
     * @param wayCode ODsay 방향 코드. null 이면 방향을 가리지 않는다
     */
    public List<Arrival> arrivals(String lineId, String stationId, Integer wayCode) {
        Optional<SubwayRealtimeSnapshot> snapshot = cachePort.readSnapshot();
        if (snapshot.isEmpty() || snapshot.get().getTrains() == null) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now(SEOUL);
        String direction = toDirection(lineId, wayCode);

        Map<String, SubwayRealtimeTrain> deduped = new LinkedHashMap<>();
        snapshot.get().getTrains().stream()
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
