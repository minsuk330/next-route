package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.subway.dto.*;
import watoo.grd.nextroute.application.subway.port.in.SubwayRealtimeQueryUseCase;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SubwayRealtimeQueryService implements SubwayRealtimeQueryUseCase {

    private static final DateTimeFormatter ISO_FMT  = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter RECV_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SubwayRealtimeCachePort cachePort;

    @Override
    public SubwayRealtimeResponse query(String lineId, List<String> stationNames, String direction) {
        Optional<SubwayRealtimeSnapshot> snapshotOpt = cachePort.readSnapshot();

        if (snapshotOpt.isEmpty()) {
            SubwayRealtimeStatus status = cachePort.readStatus()
                    .orElse(SubwayRealtimeStatus.COLD_START);
            return SubwayRealtimeResponse.builder()
                    .ageSeconds(0)
                    .status(status)
                    .trains(List.of())
                    .build();
        }

        SubwayRealtimeSnapshot snapshot = snapshotOpt.get();
        LocalDateTime now = LocalDateTime.now(KST);
        long ageSeconds = computeAge(snapshot.getCollectedAt(), now);

        List<SubwayRealtimeTrain> filtered = snapshot.getTrains().stream()
                .filter(t -> lineId == null || lineId.equals(t.getLineId()))
                .filter(t -> stationNames == null || stationNames.isEmpty()
                        || stationNames.contains(t.getStationName()))
                .filter(t -> direction == null || direction.equals(t.getDirection()))
                .map(t -> adjustArrivalSeconds(t, now))
                .toList();

        return SubwayRealtimeResponse.builder()
                .collectedAt(snapshot.getCollectedAt())
                .ageSeconds(ageSeconds)
                .status(snapshot.getStatus())
                .trains(filtered)
                .build();
    }

    private long computeAge(String collectedAt, LocalDateTime now) {
        if (collectedAt == null) return 0;
        try {
            OffsetDateTime odt = OffsetDateTime.parse(collectedAt, ISO_FMT);
            return Duration.between(odt.toLocalDateTime(), now).getSeconds();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Adjusts arrivalSeconds using receivedAt (recptnDt) as the reference point.
     * adjustedSeconds = raw - (now - receivedAt). Clamped to 0 if negative.
     */
    private SubwayRealtimeTrain adjustArrivalSeconds(SubwayRealtimeTrain train, LocalDateTime now) {
        if (train.getArrivalSeconds() == null) return train;
        int adjusted = train.getArrivalSeconds();
        if (train.getReceivedAt() != null) {
            try {
                LocalDateTime received = LocalDateTime.parse(train.getReceivedAt(), RECV_FMT);
                long elapsed = Duration.between(received, now).getSeconds();
                adjusted = (int) Math.max(0, train.getArrivalSeconds() - elapsed);
            } catch (Exception ignored) { }
        }
        return SubwayRealtimeTrain.builder()
                .trainNo(train.getTrainNo())
                .lineId(train.getLineId())
                .direction(train.getDirection())
                .stationName(train.getStationName())
                .prevStationName(train.getPrevStationName())
                .nextStationName(train.getNextStationName())
                .arrivalSeconds(adjusted)
                .receivedAt(train.getReceivedAt())
                .segmentTravelSeconds(train.getSegmentTravelSeconds())
                .nextSegmentTravelSeconds(train.getNextSegmentTravelSeconds())
                .arrivalCode(train.getArrivalCode())
                .currentMessage(train.getCurrentMessage())
                .destinationName(train.getDestinationName())
                .trainType(train.getTrainType())
                .build();
    }
}
