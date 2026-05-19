package watoo.grd.nextroute.application.subway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEvent;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayArrivalEventDerivationService {

    private static final DateTimeFormatter RAW_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Duration SPLIT_THRESHOLD = Duration.ofMinutes(10);

    private final SubwayDataService subwayDataService;
    private final ObjectMapper objectMapper;

    public int deriveForDate(LocalDate serviceDate) {
        // Step 1: raw 범위 조회
        String from = serviceDate.atTime(4, 0, 0).format(RAW_FMT);
        String to = serviceDate.plusDays(1).atTime(4, 0, 0).format(RAW_FMT);
        List<SubwayArrivalRaw> candidates = subwayDataService.findArrivalCandidatesInRange(from, to);

        // Step 2: received_at 파싱 후 유효한 row만 유지
        record RawWithTime(SubwayArrivalRaw raw, LocalDateTime receivedAt) {}

        List<RawWithTime> parsed = candidates.stream()
                .map(r -> {
                    LocalDateTime dt = parseReceivedAt(r.getReceivedAt());
                    return dt == null ? null : new RawWithTime(r, dt);
                })
                .filter(Objects::nonNull)
                .toList();

        // Step 3: 그룹 키로 묶기
        record GroupKey(String lineId, String stationId, String direction, String trainNo) {}

        Map<GroupKey, List<RawWithTime>> grouped = parsed.stream()
                .collect(Collectors.groupingBy(
                        rwt -> new GroupKey(
                                rwt.raw().getLineId(),
                                rwt.raw().getStationId(),
                                rwt.raw().getDirection(),
                                rwt.raw().getTrainNo()
                        )
                ));

        // Step 4 & 5: Train Number Compression (10분 split) + 이벤트 속성 계산
        List<SubwayArrivalEvent> events = new ArrayList<>();

        for (Map.Entry<GroupKey, List<RawWithTime>> entry : grouped.entrySet()) {
            GroupKey key = entry.getKey();

            // Step 4: split by 10-minute gap (공용 유틸 — Phase C와 동일 규칙)
            List<List<RawWithTime>> subGroups = TimeGapSplitter.splitByGap(
                    entry.getValue(), RawWithTime::receivedAt, SPLIT_THRESHOLD);

            // Step 5: 이벤트 속성 계산
            for (List<RawWithTime> sub : subGroups) {
                LocalDateTime arrivedAt = sub.stream()
                        .map(RawWithTime::receivedAt)
                        .min(Comparator.naturalOrder())
                        .orElseThrow();
                LocalDateTime lastObservedAt = sub.stream()
                        .map(RawWithTime::receivedAt)
                        .max(Comparator.naturalOrder())
                        .orElseThrow();
                int rawCount = sub.size();

                // destinationKey 결정
                RawWithTime destinationRow = sub.stream()
                        .filter(r -> r.raw().getDestinationId() != null)
                        .min(Comparator.comparing(RawWithTime::receivedAt))
                        .orElse(null);

                if (destinationRow == null) {
                    destinationRow = sub.stream()
                            .filter(r -> r.raw().getDestinationName() != null)
                            .min(Comparator.comparing(RawWithTime::receivedAt))
                            .orElse(null);
                }

                String destinationKey;
                String destinationId;
                String destinationName;

                if (destinationRow != null) {
                    destinationId = destinationRow.raw().getDestinationId();
                    destinationName = destinationRow.raw().getDestinationName();
                    destinationKey = destinationId != null ? destinationId : destinationName;
                } else {
                    destinationKey = "UNKNOWN";
                    destinationId = null;
                    destinationName = null;
                }

                // destinationConflicted 판단
                Set<String> distinctDestinations = sub.stream()
                        .map(r -> {
                            String did = r.raw().getDestinationId();
                            String dname = r.raw().getDestinationName();
                            return did != null ? did : (dname != null ? dname : "UNKNOWN");
                        })
                        .collect(Collectors.toSet());
                boolean destinationConflicted = distinctDestinations.size() > 1;
                int destinationConflictCount = distinctDestinations.size();

                // trainType: non-null인 첫 번째 row
                String trainType = sub.stream()
                        .map(r -> r.raw().getTrainType())
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);

                // stationName: non-null인 첫 번째 row
                String stationName = sub.stream()
                        .map(r -> r.raw().getStationName())
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);

                // sourceRawIds 직렬화
                List<Long> rawIds = sub.stream()
                        .map(r -> r.raw().getId())
                        .collect(Collectors.toList());
                String sourceRawIds;
                try {
                    sourceRawIds = objectMapper.writeValueAsString(rawIds);
                } catch (Exception e) {
                    log.warn("[EventDerivation] Failed to serialize sourceRawIds for key={}: {}", key, e.getMessage());
                    sourceRawIds = "[]";
                }

                SubwayArrivalEvent event = SubwayArrivalEvent.builder()
                        .serviceDate(serviceDate)
                        .lineId(key.lineId())
                        .stationId(key.stationId())
                        .stationName(stationName)
                        .trainNo(key.trainNo())
                        .direction(key.direction())
                        .destinationKey(destinationKey)
                        .destinationId(destinationId)
                        .destinationName(destinationName)
                        .trainType(trainType)
                        .eventSource("OBSERVED_CODE_1")
                        .destinationConflicted(destinationConflicted)
                        .destinationConflictCount(destinationConflictCount)
                        .arrivedAt(arrivedAt)
                        .firstObservedAt(arrivedAt)
                        .lastObservedAt(lastObservedAt)
                        .rawCount(rawCount)
                        .sourceRawIds(sourceRawIds)
                        .build();

                events.add(event);
            }
        }

        // Step 6: Delete → Insert
        subwayDataService.deleteArrivalEventsByServiceDate(serviceDate);
        List<SubwayArrivalEvent> saved = subwayDataService.saveAllArrivalEvents(events);
        log.info("[EventDerivation] serviceDate={} → deleted existing, inserted {} events from {} candidates",
                serviceDate, saved.size(), candidates.size());
        return saved.size();
    }

    private LocalDateTime parseReceivedAt(String raw) {
        try {
            return LocalDateTime.parse(raw, RAW_FMT);
        } catch (Exception e) {
            return null;
        }
    }
}
