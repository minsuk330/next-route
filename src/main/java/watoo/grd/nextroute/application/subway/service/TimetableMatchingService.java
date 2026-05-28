package watoo.grd.nextroute.application.subway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairer.MappingMissing;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairer.PairingResult;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairer.UnmatchedEvent;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairer.UnmatchedTimetable;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairerV2.CountMismatchGroup;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairerV2.DestinationMismatchGroup;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairerV2.RejectedByTimeDistanceGroup;
import watoo.grd.nextroute.domain.subway.entity.MatchIssueType;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEvent;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEventMatchIssue;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.entity.SubwayTimetable;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 실패 진단 서비스 — event ↔ timetable 매칭 후 issue table에 저장한다.
 *
 * <p>두 가지 매칭 버전을 지원한다 ({@code batch.delay-truth.matching-version}).
 *
 * <ul>
 *   <li><b>v1</b> (legacy): {@link EventTimetablePairer} 기반. issue 종류
 *       {@code MAPPING_MISSING}, {@code NO_RAW_EVENT}, {@code EXTRA_RAW_EVENT}.</li>
 *   <li><b>v2</b> (default): {@link EventTimetablePairerV2} 기반.
 *       {@code count guard + destination hard reject + time-window guard} 적용 후
 *       {@code MAPPING_MISSING}, {@code COUNT_MISMATCH}, {@code MATCH_REJECTED_TIME_DISTANCE},
 *       {@code DESTINATION_MISMATCH}. 정상 row 진단 (NO/EXTRA RAW_EVENT)은
 *       그룹 단위 {@code COUNT_MISMATCH}로 흡수된다.</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class TimetableMatchingService {

    private final SubwayDataService subwayDataService;
    private final TimetableConverter converter;
    private final EventTimetablePairer pairer;
    private final EventTimetablePairerV2 pairerV2;
    private final DestinationNormalizer destinationNormalizer;
    private final ObjectMapper objectMapper;

    @Value("${batch.delay-truth.matching-version:v2}")
    String matchingVersion;

    @Value("${batch.delay-truth.max-match-distance-seconds:1800}")
    long maxMatchDistanceSeconds;

    public TimetableMatchingService(SubwayDataService subwayDataService,
                                    TimetableConverter converter,
                                    EventTimetablePairer pairer,
                                    EventTimetablePairerV2 pairerV2,
                                    DestinationNormalizer destinationNormalizer,
                                    ObjectMapper objectMapper) {
        this.subwayDataService = subwayDataService;
        this.converter = converter;
        this.pairer = pairer;
        this.pairerV2 = pairerV2;
        this.destinationNormalizer = destinationNormalizer;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int matchForDate(LocalDate serviceDate) {
        if ("v2".equalsIgnoreCase(matchingVersion)) {
            return matchForDateV2(serviceDate);
        }
        return matchForDateV1(serviceDate);
    }

    // ── V1 (legacy) ─────────────────────────────────────────────────────────

    private int matchForDateV1(LocalDate serviceDate) {
        subwayDataService.deleteMatchIssuesByServiceDate(serviceDate);

        List<SubwayArrivalEvent> events = subwayDataService.findArrivalEventsByServiceDate(serviceDate);
        String dayType = converter.toDayType(serviceDate);
        List<SubwayStation> mappableStations = subwayDataService.findMappableStations();

        Set<String> lineIds = new HashSet<>();
        for (SubwayStation st : mappableStations) lineIds.add(st.getLineId());
        for (SubwayArrivalEvent ev : events) lineIds.add(ev.getLineId());
        List<SubwayTimetable> timetables =
                subwayDataService.findTimetablesByDayTypeAndLineIdIn(dayType, lineIds);

        PairingResult result = pairer.pair(serviceDate, dayType, events, mappableStations, timetables);

        result.invalidEvents().forEach(iv ->
                log.warn("[PhaseB] direction 변환 실패, skip event id={} direction={}",
                        iv.event().getId(), iv.event().getDirection()));

        List<SubwayArrivalEventMatchIssue> issues = new ArrayList<>();

        for (MappingMissing mm : result.mappingMissing()) {
            issues.add(SubwayArrivalEventMatchIssue.builder()
                    .serviceDate(serviceDate)
                    .issueType(MatchIssueType.MAPPING_MISSING.name())
                    .lineId(mm.key().lineId())
                    .stationId(mm.key().stationId())
                    .direction(mm.key().directionUD())
                    .dayType(dayType)
                    .matchGroupKey(mm.matchGroupKey())
                    .arrivalEventId(mm.event().getId())
                    .actualArrivedAt(mm.event().getArrivedAt())
                    .eventOrderIndex(mm.eventIndex())
                    .timetableCount(0)
                    .eventCount(mm.eventCount())
                    .build());
        }

        for (UnmatchedTimetable ut : result.unmatchedTimetables()) {
            issues.add(SubwayArrivalEventMatchIssue.builder()
                    .serviceDate(serviceDate)
                    .issueType(MatchIssueType.NO_RAW_EVENT.name())
                    .lineId(ut.key().lineId())
                    .stationId(ut.key().stationId())
                    .stationName(ut.station().getStationName())
                    .direction(ut.key().directionUD())
                    .dayType(dayType)
                    .matchGroupKey(ut.matchGroupKey())
                    .timetableId(ut.timetable().timetable().getId())
                    .scheduledArrivalAt(ut.timetable().scheduledArrivalAt())
                    .scheduledTimeSource(ut.timetable().scheduledTimeSource())
                    .timetableOrderIndex(ut.orderIndex())
                    .timetableCount(ut.timetableCount())
                    .eventCount(ut.eventCount())
                    .build());
        }

        for (UnmatchedEvent ue : result.unmatchedEvents()) {
            issues.add(SubwayArrivalEventMatchIssue.builder()
                    .serviceDate(serviceDate)
                    .issueType(MatchIssueType.EXTRA_RAW_EVENT.name())
                    .lineId(ue.key().lineId())
                    .stationId(ue.key().stationId())
                    .stationName(ue.station().getStationName())
                    .direction(ue.key().directionUD())
                    .dayType(dayType)
                    .matchGroupKey(ue.matchGroupKey())
                    .arrivalEventId(ue.event().event().getId())
                    .actualArrivedAt(ue.event().event().getArrivedAt())
                    .eventOrderIndex(ue.orderIndex())
                    .timetableCount(ue.timetableCount())
                    .eventCount(ue.eventCount())
                    .build());
        }

        long mappingMissingCount = result.mappingMissing().size();
        long noRawEventCount = result.unmatchedTimetables().size();
        long extraRawEventCount = result.unmatchedEvents().size();

        subwayDataService.saveAllMatchIssues(issues);

        log.info("[PhaseB v1] serviceDate={} MAPPING_MISSING={} NO_RAW_EVENT={} EXTRA_RAW_EVENT={} total={}",
                serviceDate, mappingMissingCount, noRawEventCount, extraRawEventCount, issues.size());

        return issues.size();
    }

    // ── V2 ────────────────────────────────────────────────────────────────

    private int matchForDateV2(LocalDate serviceDate) {
        subwayDataService.deleteMatchIssuesByServiceDate(serviceDate);

        List<SubwayArrivalEvent> events = subwayDataService.findArrivalEventsByServiceDate(serviceDate);
        String dayType = converter.toDayType(serviceDate);
        List<SubwayStation> mappableStations = subwayDataService.findMappableStations();

        Set<String> lineIds = new HashSet<>();
        for (SubwayStation st : mappableStations) lineIds.add(st.getLineId());
        for (SubwayArrivalEvent ev : events) lineIds.add(ev.getLineId());
        List<SubwayTimetable> timetables =
                subwayDataService.findTimetablesByDayTypeAndLineIdIn(dayType, lineIds);

        List<SubwayArrivalEvent> filteredEvents = events.stream()
                .filter(ev -> ev.getArrivedAt() != null)
                .toList();
        List<SubwayTimetable> filteredTimetables = timetables.stream()
                .filter(tt -> converter.toScheduledArrivalAt(
                        serviceDate, tt.getArrTime(), tt.getDepTime()) != null)
                .toList();

        EventTimetablePairerV2.PairingResult result = pairerV2.pair(
                serviceDate, dayType, filteredEvents, mappableStations, filteredTimetables,
                maxMatchDistanceSeconds);

        result.invalidEvents().forEach(iv ->
                log.warn("[PhaseB v2] direction 변환 실패, skip event id={} direction={}",
                        iv.event().getId(), iv.event().getDirection()));

        List<SubwayArrivalEventMatchIssue> issues = new ArrayList<>();

        for (MappingMissing mm : result.mappingMissing()) {
            issues.add(SubwayArrivalEventMatchIssue.builder()
                    .serviceDate(serviceDate)
                    .issueType(MatchIssueType.MAPPING_MISSING.name())
                    .lineId(mm.key().lineId())
                    .stationId(mm.key().stationId())
                    .direction(mm.key().directionUD())
                    .dayType(dayType)
                    .matchGroupKey(mm.matchGroupKey())
                    .arrivalEventId(mm.event().getId())
                    .actualArrivedAt(mm.event().getArrivedAt())
                    .eventOrderIndex(mm.eventIndex())
                    .timetableCount(0)
                    .eventCount(mm.eventCount())
                    .build());
        }

        // COUNT_MISMATCH — 그룹 단위 한 row
        for (CountMismatchGroup g : result.countMismatch()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("subtype", g.eventCount() < g.timetableCount()
                    ? "TIMETABLE_MORE_THAN_EVENT" : "EVENT_MORE_THAN_TIMETABLE");
            details.put("missingSlots", Math.max(g.timetableCount() - g.eventCount(), 0));
            details.put("extraEvents", Math.max(g.eventCount() - g.timetableCount(), 0));

            issues.add(SubwayArrivalEventMatchIssue.builder()
                    .serviceDate(serviceDate)
                    .issueType(MatchIssueType.COUNT_MISMATCH.name())
                    .lineId(g.key().lineId())
                    .stationId(g.key().stationId())
                    .stationName(g.station().getStationName())
                    .tagoStationId(g.station().getTagoStationId())
                    .direction(g.key().directionUD())
                    .dayType(dayType)
                    .matchGroupKey(g.matchGroupKey())
                    .timetableCount(g.timetableCount())
                    .eventCount(g.eventCount())
                    .details(toJson(details))
                    .build());
        }

        // MATCH_REJECTED_TIME_DISTANCE — group의 각 pair마다 row (분석 트레이스용)
        for (RejectedByTimeDistanceGroup g : result.rejectedByTimeDistance()) {
            for (var p : g.rejectedPairs()) {
                long delaySec = Duration.between(
                        p.timetable().scheduledArrivalAt(),
                        p.event().event().getArrivedAt()).getSeconds();
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("delaySeconds", delaySec);
                details.put("maxAbsDelaySecondsInGroup", g.maxAbsDelaySeconds());
                details.put("rejectionReason", "TIME_DISTANCE");

                issues.add(SubwayArrivalEventMatchIssue.builder()
                        .serviceDate(serviceDate)
                        .issueType(MatchIssueType.MATCH_REJECTED_TIME_DISTANCE.name())
                        .lineId(g.key().lineId())
                        .stationId(g.key().stationId())
                        .stationName(g.station().getStationName())
                        .tagoStationId(g.station().getTagoStationId())
                        .direction(g.key().directionUD())
                        .dayType(dayType)
                        .matchGroupKey(g.matchGroupKey())
                        .timetableId(p.timetable().timetable().getId())
                        .arrivalEventId(p.event().event().getId())
                        .scheduledArrivalAt(p.timetable().scheduledArrivalAt())
                        .actualArrivedAt(p.event().event().getArrivedAt())
                        .scheduledTimeSource(p.timetable().scheduledTimeSource())
                        .timetableOrderIndex(p.timetableOrderIndex())
                        .eventOrderIndex(p.eventOrderIndex())
                        .timetableCount(g.timetableCount())
                        .eventCount(g.eventCount())
                        .details(toJson(details))
                        .build());
            }
        }

        // DESTINATION_MISMATCH — group의 각 pair마다 row
        for (DestinationMismatchGroup g : result.destinationMismatch()) {
            for (var p : g.rejectedPairs()) {
                var match = destinationNormalizer.compare(
                        p.event().event().getDestinationName(),
                        p.timetable().timetable().getEndStationName());
                if (match != DestinationNormalizer.Match.KNOWN_MISMATCH) {
                    // group 안 다른 pair가 mismatch였을 수 있음 → 본 pair는 진단 row 생략
                    continue;
                }
                long delaySec = Duration.between(
                        p.timetable().scheduledArrivalAt(),
                        p.event().event().getArrivedAt()).getSeconds();
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("destinationEvent", p.event().event().getDestinationName());
                details.put("destinationTimetable", p.timetable().timetable().getEndStationName());
                details.put("delaySeconds", delaySec);
                details.put("rejectionReason", "DESTINATION_MISMATCH");

                issues.add(SubwayArrivalEventMatchIssue.builder()
                        .serviceDate(serviceDate)
                        .issueType(MatchIssueType.DESTINATION_MISMATCH.name())
                        .lineId(g.key().lineId())
                        .stationId(g.key().stationId())
                        .stationName(g.station().getStationName())
                        .tagoStationId(g.station().getTagoStationId())
                        .direction(g.key().directionUD())
                        .dayType(dayType)
                        .matchGroupKey(g.matchGroupKey())
                        .timetableId(p.timetable().timetable().getId())
                        .arrivalEventId(p.event().event().getId())
                        .scheduledArrivalAt(p.timetable().scheduledArrivalAt())
                        .actualArrivedAt(p.event().event().getArrivedAt())
                        .scheduledTimeSource(p.timetable().scheduledTimeSource())
                        .timetableOrderIndex(p.timetableOrderIndex())
                        .eventOrderIndex(p.eventOrderIndex())
                        .timetableCount(g.timetableCount())
                        .eventCount(g.eventCount())
                        .details(toJson(details))
                        .build());
            }
        }

        subwayDataService.saveAllMatchIssues(issues);

        log.info("[PhaseB v2] serviceDate={} MAPPING_MISSING={} COUNT_MISMATCH={} "
                        + "MATCH_REJECTED_TIME_DISTANCE_groups={} DESTINATION_MISMATCH_groups={} "
                        + "matched_pairs={} total_issues={}",
                serviceDate,
                result.mappingMissing().size(),
                result.countMismatch().size(),
                result.rejectedByTimeDistance().size(),
                result.destinationMismatch().size(),
                result.matched().size(),
                issues.size());

        return issues.size();
    }

    private String toJson(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            log.warn("[PhaseB v2] details 직렬화 실패: {}", e.getMessage());
            return null;
        }
    }
}
