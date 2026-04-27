package watoo.grd.nextroute.application.arrival.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.arrival.dto.SubwayArrivalResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayArrivalUseCase;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
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
    public List<SubwayArrivalResponse> getArrivals(String stationRaw) {
        int sep = stationRaw.lastIndexOf('_');
        if (sep < 0) return List.of();

        String rawName  = stationRaw.substring(0, sep);
        String linePart = stationRaw.substring(sep + 1);
        String stationName = rawName.endsWith("역")
                ? rawName.substring(0, rawName.length() - 1) : rawName;

        SubwayStation matched =
                subwayDataService.findByStationNameLikeAndLineName(stationName, linePart);
        if (matched == null) return List.of();

        Optional<SubwayRealtimeSnapshot> snapshot = cachePort.readSnapshot();
        return fromSnapshot(snapshot, matched.getLineId(), stationName, null);
    }

    @Override
    public List<SubwayArrivalResponse> getArrivalsById(String stationId, Integer wayCode) {
        if (stationId.length() == 3) stationId = "0" + stationId;
        Optional<SubwayStation> stationOpt = subwayDataService.findByStationId(stationId);
        if (stationOpt.isEmpty()) return List.of();

        SubwayStation station    = stationOpt.get();
        String targetLineId      = station.getLineId();
        String direction         = toDirection(targetLineId, wayCode);

        String raw      = station.getStationName();
        int parenIdx    = raw.indexOf('(');
        String trimmed  = parenIdx >= 0 ? raw.substring(0, parenIdx) : raw;
        String stationName = trimmed.endsWith("역")
                ? trimmed.substring(0, trimmed.length() - 1) : trimmed;

        Optional<SubwayRealtimeSnapshot> snapshot = cachePort.readSnapshot();
        return fromSnapshot(snapshot, targetLineId, stationName, direction);
    }

    private List<SubwayArrivalResponse> fromSnapshot(
            Optional<SubwayRealtimeSnapshot> snapshotOpt,
            String lineId, String stationName, String direction) {

        if (snapshotOpt.isEmpty()) return List.of();
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));

        Map<String, SubwayRealtimeTrain> deduped = new LinkedHashMap<>();
        snapshotOpt.get().getTrains().stream()
                .filter(t -> lineId.equals(t.getLineId()))
                .filter(t -> stationName.equals(t.getStationName()))
                .filter(t -> direction == null || direction.equals(t.getDirection()))
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
