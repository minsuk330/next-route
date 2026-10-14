package watoo.grd.nextroute.application.subway.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import watoo.grd.nextroute.application.journey.reeval.SubwayChangeFeed;
import watoo.grd.nextroute.common.lock.LeaderLease;
import watoo.grd.nextroute.application.subway.observability.SubwayRealtimeMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
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
@Profile("worker")
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
    private final SubwaySegmentTimeRecorder segmentTimeRecorder;
    private final SubwayArrivalDepartureRecorder arrivalDepartureRecorder;
    private final SubwayRealtimeMetrics metrics;
    /** 스냅숏에서 바뀐 역을 찾아 그 역을 지켜보는 여정의 평가를 앞당긴다(#415-3). */
    private final SubwayChangeFeed changeFeed;
    /**
     * 여러 worker 중 한 대만 수집하게 한다({@code journey.navigation.reeval.leader-lease.enabled}). 끄면(기본)
     * 늘 리더라 지금과 같다. 변화 감지와 같은 잠금이라 수집하는 인스턴스가 곧 감지하는 인스턴스다.
     */
    private final LeaderLease leaderLease;

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
        if (!leaderLease.isLeader()) {
            // 다른 worker 가 수집한다. 두 대가 같이 부르면 호출 한도와 수신 트래픽이 두 배가 된다.
            return;
        }

        if (!isActiveHours()) {
            cachePort.saveStatus(SubwayRealtimeStatus.OFF_HOURS);

            return;
        }

        long startedAt = System.nanoTime();
        try {
            /// api로 조회
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
                    log.warn("[RealtimeCollector] Failed to persist arrival raw rows: {}", arrivals.size(),e);
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
            try {
                changeFeed.onSnapshot(trains);
            } catch (Exception e) {
                // 재평가 트리거가 실패해도 수집은 성공이다. 평가기의 간격 폴링이 안전망이다.
                log.warn("[RealtimeCollector] 변화 감지 실패: {}", e.getMessage());
            }
            try {
                // 진행 링의 도착 예정 원천(구간 실측). 실패해도 수집은 성공이다.
                segmentTimeRecorder.record(trains);
            } catch (Exception e) {
                log.warn("[RealtimeCollector] 구간 실측 기록 실패: {}", e.getMessage());
            }
            try {
                // 위치 API 없는 노선의 떠난 열차(#317). 실패해도 수집은 성공이다.
                arrivalDepartureRecorder.record(trains, java.time.Instant.now());
            } catch (Exception e) {
                log.warn("[RealtimeCollector] 도착 출발 기록 실패: {}", e.getMessage());
            }
            //log.info("[RealtimeCollector] Saved {} trains to Redis", trains.size());

        } catch (Exception e) {
            log.error("[RealtimeCollector] Collection failed: {}", e.getMessage());
            writeErrorSnapshot();
        } finally {
            metrics.recordCollectCycle("arrival", "all", System.nanoTime() - startedAt);
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
                .prevStationId(info.prevStationId())
                .nextStationName(nextName)
                .arrivalSeconds(info.arrivalSeconds())
                .receivedAt(info.receivedAt())
                .segmentTravelSeconds(segTravel)
                .nextSegmentTravelSeconds(nextSegTravel)
                .arrivalCode(info.arrivalCode())
                .currentMessage(info.currentMessage())
                .currentStationName(info.arrivalMsg3())
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
     * Active window: 04:30 ~ 01:00 (KST, crosses midnight)
     * Inactive window: 01:00 ≤ now < 04:30
     */
    private boolean isActiveHours() {
        LocalTime now   = LocalTime.now(KST);
        LocalTime end   = LocalTime.parse(activeEnd);
        LocalTime start = LocalTime.parse(activeStart);
        // Inactive: [00:30, 05:30)
        boolean inactive = !now.isBefore(end) && now.isBefore(start);
        return !inactive;
    }
}
