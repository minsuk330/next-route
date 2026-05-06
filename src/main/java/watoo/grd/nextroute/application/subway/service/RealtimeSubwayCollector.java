package watoo.grd.nextroute.application.subway.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeStatus;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
/// todo refactoring  현재 경계가 섞여 있음
public class RealtimeSubwayCollector {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Value("${realtime.subway.enabled:true}")
    private boolean enabled;

    @Value("${realtime.subway.active-hours.start:05:30}")
    private String activeStart;

    @Value("${realtime.subway.active-hours.end:00:30}")
    private String activeEnd;

    @Value("${realtime.subway.snapshot-ttl-seconds:25}")
    private long snapshotTtlSeconds;

    @Value("${realtime.subway.persist-raw-arrivals:true}")
    private boolean persistRawArrivals;

    private final SubwayApiPort subwayApiPort;
    private final SubwayRealtimeCachePort cachePort;
    private final SubwaySegmentLookup segmentLookup;
    private final SubwayStationIdLookup stationIdLookup;
    private final SubwayArrivalRawRecorder arrivalRawRecorder;

    @PostConstruct
    public void registerBootTime() {
        try {
            String bootTime = OffsetDateTime.now(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            cachePort.saveBootTime(bootTime);
            log.info("[RealtimeCollector] Boot time registered: {}", bootTime);
        } catch (Exception e) {
            log.warn("[RealtimeCollector] Failed to register boot time: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${realtime.subway.poll-interval-ms:15000}")
    public void collect() {
        if (!enabled) return;

        if (!isActiveHours()) {
            cachePort.saveStatus(SubwayRealtimeStatus.OFF_HOURS);
            log.debug("[RealtimeCollector] Off-hours, skipping collection");
            return;
        }

        try {
            List<SubwayArrivalInfo> arrivals = subwayApiPort.getRealtimeArrival();
            if (arrivals.isEmpty()) {
                log.warn("[RealtimeCollector] Empty arrivals from API");
                writeErrorSnapshot();
                return;
            }

            if (persistRawArrivals) {
                try {
                    arrivalRawRecorder.record(arrivals);
                } catch (Exception e) {
                    log.warn("[RealtimeCollector] Failed to persist arrival raw rows: {}", e.getMessage());
                }
            }

            stationIdLookup.update(arrivals);

            List<SubwayRealtimeTrain> trains = arrivals.stream()
                    .map(this::toTrain)
                    .toList();

            SubwayRealtimeSnapshot snapshot = SubwayRealtimeSnapshot.builder()
                    .collectedAt(OffsetDateTime.now(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    .status(SubwayRealtimeStatus.ACTIVE)
                    .trains(trains)
                    .build();

            cachePort.saveSnapshot(snapshot, snapshotTtlSeconds);
            cachePort.saveStatus(SubwayRealtimeStatus.ACTIVE);
            log.info("[RealtimeCollector] Saved {} trains to Redis", trains.size());

        } catch (Exception e) {
            log.error("[RealtimeCollector] Collection failed: {}", e.getMessage());
            writeErrorSnapshot();
        }
    }

    private SubwayRealtimeTrain toTrain(SubwayArrivalInfo info) {
        String prevName = stationIdLookup.getStationName(info.prevStationId());
        String nextName = stationIdLookup.getStationName(info.nextStationId());
        Double segTravel = segmentLookup.get(info.lineId(), prevName, info.stationName());
        Double nextSegTravel = segmentLookup.get(info.lineId(), info.stationName(), nextName);

        return SubwayRealtimeTrain.builder()
                .trainNo(info.trainNo())
                .stationId(info.stationId())
                .lineId(info.lineId())
                .direction(info.direction())
                .stationName(info.stationName())
                .prevStationName(prevName)
                .nextStationName(nextName)
                .arrivalSeconds(info.arrivalSeconds())
                .receivedAt(info.receivedAt())
                .segmentTravelSeconds(segTravel)
                .nextSegmentTravelSeconds(nextSegTravel)
                .arrivalCode(info.arrivalCode())
                .currentMessage(info.currentMessage())
                .destinationName(info.destinationName())
                .trainType(info.trainType())
                .build();
    }

    private void writeErrorSnapshot() {
        try {
            SubwayRealtimeSnapshot errorSnapshot = SubwayRealtimeSnapshot.builder()
                    .collectedAt(OffsetDateTime.now(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    .status(SubwayRealtimeStatus.COLLECTOR_ERROR)
                    .trains(List.of())
                    .build();
            cachePort.saveSnapshot(errorSnapshot, snapshotTtlSeconds);
            cachePort.saveStatus(SubwayRealtimeStatus.COLLECTOR_ERROR);
        } catch (Exception redisEx) {
            log.warn("[RealtimeCollector] Failed to write error snapshot: {}", redisEx.getMessage());
        }
    }

    /**
     * Active window: 05:30 ~ 00:30 (KST, crosses midnight)
     * Inactive window: 00:30 ≤ now < 05:30
     */
    private boolean isActiveHours() {
        LocalTime now   = LocalTime.now(KST);
        LocalTime end   = LocalTime.parse(activeEnd);    // 00:30
        LocalTime start = LocalTime.parse(activeStart);  // 05:30
        // Inactive: [00:30, 05:30)
        boolean inactive = !now.isBefore(end) && now.isBefore(start);
        return !inactive;
    }
}
