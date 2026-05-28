package watoo.grd.nextroute.application.subway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEvent;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEventMatchIssue;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase C — code=3(전역출발)로 NO_RAW_EVENT를 보완한다.
 *
 * <p>핵심 원칙: code=3 raw를 전수 event로 만들지 않고, B-1이 남긴 NO timetable slot에
 * order 기반으로 매칭된 후보만 {@code INFERRED_FROM_PREV_DEPARTURE} event로 저장한다.
 * (rev2 플랜 D1~D4)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayInferredArrivalCompletionService {

    public static final String EVENT_SOURCE = "INFERRED_FROM_PREV_DEPARTURE";
    private static final String OBSERVED_CODE_1 = "OBSERVED_CODE_1";

    private static final DateTimeFormatter RAW_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Duration SPLIT_THRESHOLD = Duration.ofMinutes(10);

    private final SubwayDataService subwayDataService;
    private final SubwaySegmentLookup segmentLookup;
    private final TimetableConverter converter;
    private final ObjectMapper objectMapper;

    @Value("${batch.inferred-completion.line-ids:1002,1005,1006,1007}")
    private String lineIdsCsv;

    @Value("${batch.inferred-completion.dedup-window-minutes:5}")
    private long dedupWindowMinutes;

    /** v1: NO_RAW_EVENT slot 별 카운트 / v2: COUNT_MISMATCH group의 (tt - ev) */
    @Value("${batch.delay-truth.matching-version:v2}")
    String matchingVersion;

    private record CompareKey(String lineId, String stationId, String directionUD) {}

    private record Code3Raw(SubwayArrivalRaw raw, LocalDateTime receivedAt, String dirUD) {}

    private record GroupKey(String lineId, String stationId, String dirUD, String trainNo) {}

    /** 압축된 code=3 subgroup 한 건 → inferred 후보 */
    private record Candidate(CompareKey key, String rawDirection, String trainNo,
                             LocalDateTime arrivedAt, LocalDateTime firstObservedAt,
                             LocalDateTime lastObservedAt, int rawCount, String sourceRawIds,
                             String stationName, String destinationId, String destinationName,
                             String destinationKey, boolean destinationConflicted,
                             int destinationConflictCount, String trainType, long orderKey) {}

    public int completeForDate(LocalDate serviceDate) {
        List<String> lineIds = Arrays.stream(lineIdsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (lineIds.isEmpty()) {
            log.warn("[PhaseC] line-ids 비어 있음, skip serviceDate={}", serviceDate);
            return 0;
        }

        // D4: 재실행 멱등성 — 자체 event_source분만 제거 후 재삽입
        int deleted = subwayDataService.deleteArrivalEventsByServiceDateAndEventSource(serviceDate, EVENT_SOURCE);

        // B-1이 남긴 보강 대상 slot (대상 라인 한정)
        // - v1: NO_RAW_EVENT row마다 slot 1개
        // - v2: COUNT_MISMATCH group에서 max(timetable_count - event_count, 0) slot
        boolean v2 = "v2".equalsIgnoreCase(matchingVersion);
        List<SubwayArrivalEventMatchIssue> noIssues = v2
                ? subwayDataService.findCountMismatchIssues(serviceDate, lineIds)
                : subwayDataService.findNoRawEventIssues(serviceDate, lineIds);
        if (noIssues.isEmpty()) {
            log.info("[PhaseC {}] 보강 대상 issue 없음 serviceDate={} lines={} (deleted prior inferred={})",
                    matchingVersion, serviceDate, lineIds, deleted);
            return 0;
        }

        // CompareKey(lineId, stationId, directionUD) → NO slot 수
        Map<CompareKey, Integer> noSlotCountByKey = new HashMap<>();
        for (SubwayArrivalEventMatchIssue iss : noIssues) {
            CompareKey key = new CompareKey(iss.getLineId(), iss.getStationId(), iss.getDirection());
            if (v2) {
                int tt = iss.getTimetableCount() == null ? 0 : iss.getTimetableCount();
                int ev = iss.getEventCount() == null ? 0 : iss.getEventCount();
                int need = Math.max(tt - ev, 0);
                if (need <= 0) continue; // event > timetable → 보강 대상 아님
                noSlotCountByKey.merge(key, need, Integer::sum);
            } else {
                noSlotCountByKey.merge(key, 1, Integer::sum);
            }
        }
        if (noSlotCountByKey.isEmpty()) {
            log.info("[PhaseC {}] 보강 slot 없음 serviceDate={} lines={} (issues={}, deleted prior inferred={})",
                    matchingVersion, serviceDate, lineIds, noIssues.size(), deleted);
            return 0;
        }

        // 시간 범위: Phase A와 동일 (당일 04:00 ~ 익일 04:00)
        String from = serviceDate.atTime(4, 0, 0).format(RAW_FMT);
        String to = serviceDate.plusDays(1).atTime(4, 0, 0).format(RAW_FMT);

        List<SubwayArrivalRaw> code3 =
                subwayDataService.findPrevDepartureCandidatesInRange(from, to, lineIds);

        // stationId → stationName (segment lookup arrive측 + prevName 해소용, 1쿼리)
        Map<String, String> nameByStationId = new HashMap<>();
        for (SubwayStation st : subwayDataService.findAllStations()) {
            nameByStationId.putIfAbsent(st.getStationId(), st.getStationName());
        }

        // (lineId, stationId, dirUD, trainNo) 그룹핑 — directionUD 변환 실패는 제외
        Map<GroupKey, List<Code3Raw>> grouped = code3.stream()
                .map(r -> {
                    LocalDateTime dt = parseReceivedAt(r.getReceivedAt());
                    String ud = converter.toTimetableDirection(r.getDirection());
                    return (dt == null || ud == null) ? null : new Code3Raw(r, dt, ud);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(c -> new GroupKey(
                        c.raw().getLineId(), c.raw().getStationId(),
                        c.dirUD(), c.raw().getTrainNo())));

        // 압축 → 후보 (D3: segment 없으면 제외)
        Map<CompareKey, List<Candidate>> candidatesByKey = new HashMap<>();
        int segmentMiss = 0;
        for (List<Code3Raw> group : grouped.values()) {
            List<List<Code3Raw>> subGroups =
                    TimeGapSplitter.splitByGap(group, Code3Raw::receivedAt, SPLIT_THRESHOLD);
            for (List<Code3Raw> sub : subGroups) {
                SubwayArrivalRaw any = sub.get(0).raw();
                CompareKey key = new CompareKey(any.getLineId(), any.getStationId(), sub.get(0).dirUD());

                // 보완 대상 NO slot이 없는 key는 후보 생성 자체를 생략
                if (!noSlotCountByKey.containsKey(key)) {
                    continue;
                }

                String prevStationId = sub.stream()
                        .map(c -> c.raw().getPrevStationId())
                        .filter(Objects::nonNull).findFirst().orElse(null);
                String prevName = prevStationId == null ? null : nameByStationId.get(prevStationId);
                String stationName = nameByStationId.getOrDefault(any.getStationId(), any.getStationName());

                Double travelSec = segmentLookup.get(any.getLineId(), prevName, stationName);
                if (travelSec == null) {
                    segmentMiss++;
                    continue; // D3
                }

                LocalDateTime firstObserved = sub.stream().map(Code3Raw::receivedAt)
                        .min(Comparator.naturalOrder()).orElseThrow();
                LocalDateTime lastObserved = sub.stream().map(Code3Raw::receivedAt)
                        .max(Comparator.naturalOrder()).orElseThrow();
                LocalDateTime arrivedAt = firstObserved.plusSeconds(travelSec.longValue());

                candidatesByKey.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(buildCandidate(sub, key, any, firstObserved, lastObserved,
                                arrivedAt, stationName, serviceDate));
            }
        }

        // D1: 시간창 중복 제거 — OBSERVED_CODE_1 event arrived_at ±W분 내면 후보 제외
        Map<CompareKey, List<LocalDateTime>> observedByKey = new HashMap<>();
        for (SubwayArrivalEvent ev : subwayDataService.findArrivalEventsByServiceDate(serviceDate)) {
            if (!OBSERVED_CODE_1.equals(ev.getEventSource())) continue;
            String ud = converter.toTimetableDirection(ev.getDirection());
            if (ud == null) continue;
            observedByKey.computeIfAbsent(
                    new CompareKey(ev.getLineId(), ev.getStationId(), ud),
                    k -> new ArrayList<>()).add(ev.getArrivedAt());
        }
        Duration window = Duration.ofMinutes(dedupWindowMinutes);

        // 매칭(D2): NO slot 수만큼만 후보 저장, 초과분 드롭
        List<SubwayArrivalEvent> toSave = new ArrayList<>();
        int dedupDropped = 0;
        int overflowDropped = 0;
        for (Map.Entry<CompareKey, List<Candidate>> e : candidatesByKey.entrySet()) {
            CompareKey key = e.getKey();
            List<LocalDateTime> observed = observedByKey.getOrDefault(key, List.of());

            List<Candidate> kept = new ArrayList<>();
            for (Candidate c : e.getValue()) {
                boolean dup = observed.stream().anyMatch(o ->
                        Duration.between(o, c.arrivedAt()).abs().compareTo(window) <= 0);
                if (dup) {
                    dedupDropped++;
                } else {
                    kept.add(c);
                }
            }
            kept.sort(Comparator.comparingLong(Candidate::orderKey));

            int slots = noSlotCountByKey.getOrDefault(key, 0);
            int take = Math.min(slots, kept.size());
            overflowDropped += kept.size() - take;
            for (int i = 0; i < take; i++) {
                toSave.add(toEvent(kept.get(i), serviceDate));
            }
        }

        List<SubwayArrivalEvent> saved = subwayDataService.saveAllArrivalEvents(toSave);
        log.info("[PhaseC {}] serviceDate={} lines={} issues={} totalSlots={} code3_raw={} segmentMiss={} "
                        + "dedupDropped={} overflowDropped={} inferredSaved={} (deleted prior={})",
                matchingVersion, serviceDate, lineIds, noIssues.size(),
                noSlotCountByKey.values().stream().mapToInt(Integer::intValue).sum(),
                code3.size(), segmentMiss, dedupDropped, overflowDropped, saved.size(), deleted);
        return saved.size();
    }

    private Candidate buildCandidate(List<Code3Raw> sub, CompareKey key, SubwayArrivalRaw any,
                                     LocalDateTime firstObserved, LocalDateTime lastObserved,
                                     LocalDateTime arrivedAt, String stationName,
                                     LocalDate serviceDate) {
        List<SubwayArrivalRaw> raws = sub.stream().map(Code3Raw::raw).toList();

        SubwayArrivalRaw destRow = raws.stream()
                .filter(r -> r.getDestinationId() != null)
                .findFirst()
                .orElseGet(() -> raws.stream()
                        .filter(r -> r.getDestinationName() != null)
                        .findFirst().orElse(null));
        String destinationId = destRow != null ? destRow.getDestinationId() : null;
        String destinationName = destRow != null ? destRow.getDestinationName() : null;
        String destinationKey = destinationId != null ? destinationId
                : (destinationName != null ? destinationName : "UNKNOWN");

        Set<String> distinctDest = raws.stream()
                .map(r -> r.getDestinationId() != null ? r.getDestinationId()
                        : (r.getDestinationName() != null ? r.getDestinationName() : "UNKNOWN"))
                .collect(Collectors.toSet());

        String trainType = raws.stream().map(SubwayArrivalRaw::getTrainType)
                .filter(Objects::nonNull).findFirst().orElse(null);

        List<Long> rawIds = raws.stream().map(SubwayArrivalRaw::getId).toList();
        String sourceRawIds;
        try {
            sourceRawIds = objectMapper.writeValueAsString(rawIds);
        } catch (Exception ex) {
            log.warn("[PhaseC] sourceRawIds 직렬화 실패 key={}: {}", key, ex.getMessage());
            sourceRawIds = "[]";
        }

        long orderKey = converter.toEventOrderKey(serviceDate, arrivedAt);

        return new Candidate(key, any.getDirection(), any.getTrainNo(),
                arrivedAt, firstObserved, lastObserved, raws.size(), sourceRawIds,
                stationName, destinationId, destinationName, destinationKey,
                distinctDest.size() > 1, distinctDest.size(), trainType, orderKey);
    }

    private SubwayArrivalEvent toEvent(Candidate c, LocalDate serviceDate) {
        return SubwayArrivalEvent.builder()
                .serviceDate(serviceDate)
                .lineId(c.key().lineId())
                .stationId(c.key().stationId())
                .stationName(c.stationName())
                .trainNo(c.trainNo())
                .direction(c.rawDirection())
                .destinationKey(c.destinationKey())
                .destinationId(c.destinationId())
                .destinationName(c.destinationName())
                .trainType(c.trainType())
                .eventSource(EVENT_SOURCE)
                .destinationConflicted(c.destinationConflicted())
                .destinationConflictCount(c.destinationConflictCount())
                .arrivedAt(c.arrivedAt())
                .firstObservedAt(c.firstObservedAt())
                .lastObservedAt(c.lastObservedAt())
                .rawCount(c.rawCount())
                .sourceRawIds(c.sourceRawIds())
                .build();
    }

    private LocalDateTime parseReceivedAt(String raw) {
        try {
            return LocalDateTime.parse(raw, RAW_FMT);
        } catch (Exception e) {
            return null;
        }
    }
}
