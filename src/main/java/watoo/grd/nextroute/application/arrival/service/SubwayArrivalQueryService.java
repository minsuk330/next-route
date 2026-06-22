package watoo.grd.nextroute.application.arrival.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.arrival.dto.SubwayArrivalResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayArrivalUseCase;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.NearbySubwayStationProjection;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SubwayArrivalQueryService implements GetSubwayArrivalUseCase {

    private static final DateTimeFormatter RECV_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SubwayRealtimeCachePort cachePort;
    private final SubwayDataService subwayDataService;

    @Override
    public List<SubwayArrivalResponse> getArrivals(double lat, double lon, Integer wayCode, String lineId) {
        List<NearbySubwayStationProjection> nearby =
                subwayDataService.findNearbyStations(lat, lon, 50, 5);
        if (nearby.isEmpty()) return List.of();

        NearbySubwayStationProjection target = selectStation(nearby, lineId);

        Optional<SubwayRealtimeSnapshot> snapshot = cachePort.readSnapshot();

        return fromSnapshot(snapshot, target.getLineId(), target.getStationId(), wayCode);
    }

    /**
     * 후보가 2개 이상이고 line_id가 주어지면 해당 호선으로 1개만 추출.
     * 미지정/미일치 시 최근접(첫번째) 역 사용.
     */
    private NearbySubwayStationProjection selectStation(
            List<NearbySubwayStationProjection> nearby, String lineId) {
        if (nearby.size() >= 2 && lineId != null && !lineId.isBlank()) {
            return nearby.stream()
                    .filter(s -> lineId.equals(s.getLineId()))
                    .findFirst()
                    .orElse(nearby.get(0));
        }
        return nearby.get(0);
    }

    private List<SubwayArrivalResponse> fromSnapshot(
            Optional<SubwayRealtimeSnapshot> snapshotOpt,
            String lineId, String stationId, Integer wayCode) {

        if (snapshotOpt.isEmpty()) return List.of();
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));

        Map<String, SubwayRealtimeTrain> deduped = new LinkedHashMap<>();
        snapshotOpt.get().getTrains().stream()
                .filter(t -> lineId.equals(t.getLineId()))
                .filter(t -> stationId.equals(t.getStationId()))
                .filter(t -> wayCode == null || toDirection(t.getLineId(), wayCode).equals(t.getDirection()))
                .filter(t -> {
                    Integer adj = adjusted(t.getArrivalSeconds(), t.getReceivedAt(), now);
                    return adj == null || adj >= 0;
                })
                .forEach(t -> deduped.putIfAbsent(t.getDirection() + ":" + t.getTrainNo(), t));

        return deduped.values().stream()
                .sorted(Comparator.comparingInt(t ->
                        Optional.ofNullable(adjusted(t.getArrivalSeconds(), t.getReceivedAt(), now))
                                .orElse(Integer.MAX_VALUE)))
                .map(t -> SubwayArrivalResponse.builder()
                        .lineId(t.getLineId())
                        .direction(t.getDirection())
                        .arrivalSeconds(adjusted(t.getArrivalSeconds(), t.getReceivedAt(), now))
                        .currentMessage(t.getCurrentMessage())
                        .destinationName(t.getDestinationName())
                        .trainType(t.getTrainType())
                        .arrivalCode(t.getArrivalCode())
                        .build())
                .toList();
    }

    /**
     * OdSay wayCode → Seoul realtime API updnLine direction string.
     * Line 2 (1002): 0=내선순환, 1=외선순환. Others: 1=상행, 2=하행.
     */
    private String toDirection(String lineId, Integer wayCode) {
        if (wayCode == null) return null;
        if ("1002".equals(lineId)) return wayCode == 0 ? "내선" : "외선";
        return wayCode == 1 ? "상행" : "하행";
    }

    /** Adjusts arrivalSeconds by elapsed time since receivedAt (recptnDt). */
    private Integer adjusted(Integer raw, String receivedAt, LocalDateTime now) {
        if (raw == null) return null;
        if (receivedAt == null) return raw;
        try {
            LocalDateTime received = LocalDateTime.parse(receivedAt, RECV_FMT);
            long elapsed = Duration.between(received, now).getSeconds();
            return Math.toIntExact(raw - elapsed);
        } catch (Exception e) {
            return raw;
        }
    }
}
