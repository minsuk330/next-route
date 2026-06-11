package watoo.grd.nextroute.application.bus.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalCandidateRaw;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalLabelEvent;
import watoo.grd.nextroute.domain.bus.entity.BusPositionRaw;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 버스 ML 학습 라벨 생성 서비스.
 *
 * <p>bus_arrival_candidate_raw를 spine으로 삼아 service_date 단위 delete-and-insert.
 * position stop_flag=1 매칭 시 POSITION_STOP_FLAG_CORRECTED로 label 승격.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class BusArrivalLabelGenerationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FMT_NUM14 = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int STALE_POSITION_THRESHOLD_MINUTES = 2;
    private static final int TRIP_SECTION_ORDER_DROP_THRESHOLD = 5;
    private static final int TRIP_GAP_MINUTES = 30;

    private final BusDataService busDataService;

    @Value("${batch.persistence.bus-label-chunk-size:2000}")
    int chunkSize;

    @Value("${batch.bus-label.correction-window-minutes:10}")
    int correctionWindowMinutes;

    public BusArrivalLabelGenerationService(BusDataService busDataService) {
        this.busDataService = busDataService;
    }

    @Transactional
    public int generateForDate(LocalDate serviceDate) {
        LocalDateTime dayStart = serviceDate.atTime(4, 0);
        LocalDateTime dayEnd = serviceDate.plusDays(1).atTime(4, 0);

        busDataService.deleteLabelEventsByServiceDate(serviceDate);

        // 해당 service_date의 finalized candidate 전체 로드 (일 ~2.2만건)
        List<BusArrivalCandidateRaw> allCandidates =
                busDataService.findCandidatesByFinalizedAtBetween(dayStart, dayEnd);

        // 필터: lifecycle_id not null, arrival_order=1, arrival_msg 제외, vehicle_identity not null
        List<BusArrivalCandidateRaw> candidates = allCandidates.stream()
                .filter(c -> c.getLifecycleId() != null)
                .filter(c -> Integer.valueOf(1).equals(c.getArrivalOrder()))
                .filter(c -> {
                    String msg = c.getArrivalMsg();
                    return msg == null || (!msg.contains("출발대기") && !msg.contains("회차대기"));
                })
                .filter(c -> c.getVehicleIdentity() != null && !c.getVehicleIdentity().isBlank())
                .toList();

        // route 단위로 그룹 (메모리 바운드, PR #4 OOM 교훈)
        Map<String, List<BusArrivalCandidateRaw>> byRoute = candidates.stream()
                .collect(Collectors.groupingBy(BusArrivalCandidateRaw::getRouteId));

        List<BusArrivalLabelEvent> chunk = new ArrayList<>();
        int totalSaved = 0;
        int correctedCount = 0;
        int apiOnlyCount = 0;
        int excludedCount = 0;

        for (Map.Entry<String, List<BusArrivalCandidateRaw>> entry : byRoute.entrySet()) {
            String routeId = entry.getKey();
            List<BusArrivalCandidateRaw> routeCandidates = entry.getValue();

            // position 로드 (±10분 여유)
            List<BusPositionRaw> positions = busDataService
                    .findPositionsByRouteIdAndCollectedAtBetween(
                            routeId,
                            dayStart.minusMinutes(10),
                            dayEnd.plusMinutes(10));

            // position 필터: is_run_yn=1, data_tm 파싱 성공, 신선도 가드
            List<PositionSnapshot> snapshots = positions.stream()
                    .filter(p -> "1".equals(p.getIsRunYn()))
                    .map(p -> {
                        LocalDateTime ts = parseDataTm(p.getDataTm());
                        if (ts == null) return null;
                        Duration lag = Duration.between(ts, p.getCollectedAt());
                        if (lag.toMinutes() < -1 || lag.toMinutes() > STALE_POSITION_THRESHOLD_MINUTES) return null;
                        return new PositionSnapshot(p, ts);
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();

            // bus_route_stop 로드 → section_id → (stop_id, seq) 맵
            List<BusRouteStop> routeStops = busDataService.findRouteStops(routeId);
            Map<String, BusRouteStop> sectionIdToStop = routeStops.stream()
                    .collect(Collectors.toMap(BusRouteStop::getSectionId, rs -> rs,
                            (a, b) -> a)); // 중복 section_id: 첫 번째 유지(seq 오름차순은 findByRouteIdOrderBySeq 보장)

            // trip 분리 및 visit 도출
            // (vehicle_identity) 기준 시계열 → trip 분리 → stop_flag=1 visit
            Map<String, List<PositionSnapshot>> byVehicle = snapshots.stream()
                    .collect(Collectors.groupingBy(ps -> ps.raw().getVehicleId() != null
                            && !ps.raw().getVehicleId().isBlank()
                            ? ps.raw().getVehicleId()
                            : ps.raw().getPlainNo()));

            // vehicle별 visit 목록
            Map<String, List<StopVisit>> vehicleVisits = byVehicle.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> deriveVisits(routeId, e.getValue(), sectionIdToStop)));

            // candidate별 label row 생성
            for (BusArrivalCandidateRaw candidate : routeCandidates) {
                BusArrivalLabelEvent event = buildLabelEvent(
                        serviceDate, candidate, vehicleVisits, correctionWindowMinutes);

                if (event.isExcludedFromTraining()) excludedCount++;
                else if (BusArrivalLabelEvent.SOURCE_POSITION_STOP_FLAG_CORRECTED.equals(event.getLabelSource()))
                    correctedCount++;
                else
                    apiOnlyCount++;

                chunk.add(event);
                if (chunk.size() >= chunkSize) {
                    busDataService.saveAllLabelEvents(chunk);
                    busDataService.flushAndClear();
                    totalSaved += chunk.size();
                    chunk = new ArrayList<>();
                }
            }

            // route 1회전 종료: 남은 chunk 저장 후 flushAndClear.
            // 이 route에서 로드한 position 엔티티(노선당 ~3만)를 1차 캐시에서 detach해
            // 다음 route로 누적되지 않게 한다 (OOM 방지, PR #4 지하철 교훈과 동일).
            if (!chunk.isEmpty()) {
                busDataService.saveAllLabelEvents(chunk);
                totalSaved += chunk.size();
                chunk = new ArrayList<>();
            }
            busDataService.flushAndClear();
        }

        log.info("[BusLabelEvent] serviceDate={} candidates={} corrected={} apiOnly={} excluded={}",
                serviceDate, totalSaved, correctedCount, apiOnlyCount, excludedCount);
        return totalSaved;
    }

    // ── trip 분리 + visit 도출 ────────────────────────────────────────────────

    private List<StopVisit> deriveVisits(String routeId,
                                         List<PositionSnapshot> snapshots,
                                         Map<String, BusRouteStop> sectionIdToStop) {
        List<PositionSnapshot> sorted = snapshots.stream()
                .sorted(Comparator.comparing(PositionSnapshot::snapshotAt))
                .toList();

        List<List<PositionSnapshot>> trips = splitIntoTrips(sorted);

        List<StopVisit> visits = new ArrayList<>();
        for (List<PositionSnapshot> trip : trips) {
            String tripId = buildTripId(routeId, trip);
            visits.addAll(extractVisitsFromTrip(trip, tripId, sectionIdToStop));
        }
        return visits;
    }

    private List<List<PositionSnapshot>> splitIntoTrips(List<PositionSnapshot> sorted) {
        List<List<PositionSnapshot>> trips = new ArrayList<>();
        if (sorted.isEmpty()) return trips;

        List<PositionSnapshot> current = new ArrayList<>();
        current.add(sorted.get(0));

        for (int i = 1; i < sorted.size(); i++) {
            PositionSnapshot prev = sorted.get(i - 1);
            PositionSnapshot cur = sorted.get(i);

            boolean newTrip = false;
            // section_order 큰 폭 감소
            Integer prevOrd = prev.raw().getSectionOrder();
            Integer curOrd = cur.raw().getSectionOrder();
            if (prevOrd != null && curOrd != null
                    && (prevOrd - curOrd) >= TRIP_SECTION_ORDER_DROP_THRESHOLD) {
                newTrip = true;
            }
            // 30분 이상 간격
            if (!newTrip && Duration.between(prev.snapshotAt(), cur.snapshotAt()).toMinutes()
                    >= TRIP_GAP_MINUTES) {
                newTrip = true;
            }

            if (newTrip) {
                trips.add(current);
                current = new ArrayList<>();
            }
            current.add(cur);
        }
        if (!current.isEmpty()) trips.add(current);
        return trips;
    }

    private String buildTripId(String routeId, List<PositionSnapshot> trip) {
        String vehicleId = trip.get(0).raw().getVehicleId();
        if (vehicleId == null || vehicleId.isBlank()) vehicleId = trip.get(0).raw().getPlainNo();
        return routeId + ":" + vehicleId + ":" + trip.get(0).snapshotAt();
    }

    private List<StopVisit> extractVisitsFromTrip(List<PositionSnapshot> trip,
                                                   String tripId,
                                                   Map<String, BusRouteStop> sectionIdToStop) {
        List<StopVisit> visits = new ArrayList<>();
        // stop_flag=1 + section_id 묶기
        int i = 0;
        while (i < trip.size()) {
            PositionSnapshot ps = trip.get(i);
            if (!"1".equals(ps.raw().getStopFlag())) {
                i++;
                continue;
            }
            String sectionId = ps.raw().getSectionId();
            if (sectionId == null || sectionId.isBlank()) {
                i++;
                continue;
            }
            BusRouteStop routeStop = sectionIdToStop.get(sectionId);
            if (routeStop == null) {
                i++;
                continue;
            }

            // 연속 같은 section_id stop_flag=1 묶기
            List<PositionSnapshot> group = new ArrayList<>();
            group.add(ps);
            int j = i + 1;
            while (j < trip.size()
                    && "1".equals(trip.get(j).raw().getStopFlag())
                    && sectionId.equals(trip.get(j).raw().getSectionId())) {
                group.add(trip.get(j));
                j++;
            }

            LocalDateTime arrivedAt = group.get(0).snapshotAt();
            LocalDateTime departedAt = group.size() >= 2 ? group.get(group.size() - 1).snapshotAt() : null;
            Integer dwellSeconds = departedAt != null
                    ? (int) Duration.between(arrivedAt, departedAt).getSeconds()
                    : null;

            List<Long> rawIds = group.stream()
                    .map(p -> p.raw().getId())
                    .filter(java.util.Objects::nonNull)
                    .toList();

            visits.add(new StopVisit(
                    routeStop.getStopId(), routeStop.getSeq(), sectionId, tripId,
                    arrivedAt, departedAt, dwellSeconds, rawIds));
            i = j;
        }
        return visits;
    }

    // ── label row 생성 ────────────────────────────────────────────────────────

    private BusArrivalLabelEvent buildLabelEvent(LocalDate serviceDate,
                                                  BusArrivalCandidateRaw candidate,
                                                  Map<String, List<StopVisit>> vehicleVisits,
                                                  int correctionWindowMin) {
        String vehicleIdentity = candidate.getVehicleIdentity();
        String vehicleIdentityType = candidate.getVehicleIdentityType();

        // API ETA 계산
        LocalDateTime apiEta = computeApiEta(candidate);

        if (apiEta == null) {
            return BusArrivalLabelEvent.builder()
                    .serviceDate(serviceDate)
                    .routeId(candidate.getRouteId())
                    .vehicleIdentityType(vehicleIdentityType != null ? vehicleIdentityType : "UNKNOWN")
                    .vehicleIdentity(vehicleIdentity)
                    .stopId(candidate.getStopId())
                    .seq(candidate.getSeq())
                    .labelSource(BusArrivalLabelEvent.SOURCE_ARRIVAL_API_ETA)
                    .labelConfidence(BusArrivalLabelEvent.CONFIDENCE_MEDIUM)
                    .excludedFromTraining(true)
                    .excludeReason(BusArrivalLabelEvent.EXCLUDE_INVALID_API_ETA)
                    .arrivalRawId(candidate.getId())
                    .arrivalLifecycleId(candidate.getLifecycleId())
                    .build();
        }

        // position correction 시도
        List<StopVisit> visits = vehicleVisits.getOrDefault(vehicleIdentity, List.of());
        StopVisit match = findBestVisit(visits, candidate.getStopId(), candidate.getSeq(),
                apiEta, correctionWindowMin);

        if (match != null) {
            String posRawIds = match.rawIds().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(",", "[", "]"));
            return BusArrivalLabelEvent.builder()
                    .serviceDate(serviceDate)
                    .routeId(candidate.getRouteId())
                    .vehicleIdentityType(vehicleIdentityType != null ? vehicleIdentityType : "UNKNOWN")
                    .vehicleIdentity(vehicleIdentity)
                    .tripId(match.tripId())
                    .stopId(candidate.getStopId())
                    .seq(candidate.getSeq())
                    .sectionId(match.sectionId())
                    .apiEstimatedArrivalAt(apiEta)
                    .correctedArrivalAt(match.arrivedAt())
                    .labelArrivalAt(match.arrivedAt())
                    .departedAt(match.departedAt())
                    .dwellSeconds(match.dwellSeconds())
                    .labelSource(BusArrivalLabelEvent.SOURCE_POSITION_STOP_FLAG_CORRECTED)
                    .labelConfidence(BusArrivalLabelEvent.CONFIDENCE_HIGH_PROVISIONAL)
                    .correctionSource(BusArrivalLabelEvent.SOURCE_POSITION_STOP_FLAG_CORRECTED)
                    .correctionConfidence(BusArrivalLabelEvent.CONFIDENCE_HIGH_PROVISIONAL)
                    .excludedFromTraining(false)
                    .arrivalRawId(candidate.getId())
                    .arrivalLifecycleId(candidate.getLifecycleId())
                    .positionRawIds(posRawIds)
                    .build();
        }

        // API ETA fallback
        return BusArrivalLabelEvent.builder()
                .serviceDate(serviceDate)
                .routeId(candidate.getRouteId())
                .vehicleIdentityType(vehicleIdentityType != null ? vehicleIdentityType : "UNKNOWN")
                .vehicleIdentity(vehicleIdentity)
                .stopId(candidate.getStopId())
                .seq(candidate.getSeq())
                .apiEstimatedArrivalAt(apiEta)
                .labelArrivalAt(apiEta)
                .labelSource(BusArrivalLabelEvent.SOURCE_ARRIVAL_API_ETA)
                .labelConfidence(BusArrivalLabelEvent.CONFIDENCE_MEDIUM)
                .excludedFromTraining(false)
                .arrivalRawId(candidate.getId())
                .arrivalLifecycleId(candidate.getLifecycleId())
                .build();
    }

    private StopVisit findBestVisit(List<StopVisit> visits, String stopId, Integer seq,
                                     LocalDateTime apiEta, int windowMin) {
        long windowSecs = (long) windowMin * 60;
        return visits.stream()
                .filter(v -> stopId.equals(v.stopId()) && seq != null && seq.equals(v.seq()))
                .filter(v -> Math.abs(Duration.between(v.arrivedAt(), apiEta).getSeconds()) <= windowSecs)
                .min(Comparator.comparingLong(v ->
                        Math.abs(Duration.between(v.arrivedAt(), apiEta).getSeconds())))
                .orElse(null);
    }

    // ── data_tm 파서 ─────────────────────────────────────────────────────────

    static LocalDateTime parseDataTm(String dataTm) {
        if (dataTm == null) return null;
        if (dataTm.length() == 14) {
            try {
                return LocalDateTime.parse(dataTm, FMT_NUM14);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        // ISO fallback (방어용)
        try {
            return LocalDateTime.parse(dataTm.substring(0, 19));
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime computeApiEta(BusArrivalCandidateRaw candidate) {
        String dataTimestamp = candidate.getDataTimestamp();
        Integer predictTime = candidate.getPredictTime();
        if (predictTime == null || predictTime < 0) return null;
        LocalDateTime base = parseDataTm(dataTimestamp);
        if (base == null) return null;
        return base.plusSeconds(predictTime);
    }

    // ── 내부 레코드 ──────────────────────────────────────────────────────────

    record PositionSnapshot(BusPositionRaw raw, LocalDateTime snapshotAt) {}

    record StopVisit(String stopId, Integer seq, String sectionId, String tripId,
                     LocalDateTime arrivedAt, LocalDateTime departedAt, Integer dwellSeconds,
                     List<Long> rawIds) {}
}
