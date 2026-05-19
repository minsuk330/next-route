package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairer.MappingMissing;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairer.PairingResult;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairer.UnmatchedEvent;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairer.UnmatchedTimetable;
import watoo.grd.nextroute.domain.subway.entity.MatchIssueType;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEvent;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEventMatchIssue;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.entity.SubwayTimetable;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 실패 진단 서비스 — event ↔ timetable 매칭 후 NO_RAW_EVENT / EXTRA_RAW_EVENT /
 * MAPPING_MISSING 를 {@code subway_arrival_event_match_issue} 에 저장한다.
 *
 * <p>그룹핑/정렬/min-pair 페어링은 {@link EventTimetablePairer} 로 추출되어
 * delay-truth 생성 서비스와 공유된다. 본 서비스는 raw 입력을 전달하여
 * 기존 동작(사전 필터링 없음)을 그대로 유지하고, 진단 bucket만 소비한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimetableMatchingService {

    private final SubwayDataService subwayDataService;
    private final TimetableConverter converter;
    private final EventTimetablePairer pairer;

    @Transactional
    public int matchForDate(LocalDate serviceDate) {

        // Step 1: 이전 이슈 삭제
        subwayDataService.deleteMatchIssuesByServiceDate(serviceDate);

        // Step 2: 입력 로드 (기존과 동일 경로 — raw, 사전 필터링 없음)
        List<SubwayArrivalEvent> events = subwayDataService.findArrivalEventsByServiceDate(serviceDate);
        String dayType = converter.toDayType(serviceDate);
        List<SubwayStation> mappableStations = subwayDataService.findMappableStations();

        Set<String> lineIds = new HashSet<>();
        for (SubwayStation st : mappableStations) lineIds.add(st.getLineId());
        for (SubwayArrivalEvent ev : events) lineIds.add(ev.getLineId());
        List<SubwayTimetable> timetables =
                subwayDataService.findTimetablesByDayTypeAndLineIdIn(dayType, lineIds);

        // Step 3: 페어링 (공유 루틴)
        PairingResult result = pairer.pair(serviceDate, dayType, events, mappableStations, timetables);

        // direction 변환 실패 로깅 (기존 동작 보존)
        result.invalidEvents().forEach(iv ->
                log.warn("[PhaseB] direction 변환 실패, skip event id={} direction={}",
                        iv.event().getId(), iv.event().getDirection()));

        // Step 4: 진단 bucket → issue
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

        // Step 5: 저장 및 로깅
        long mappingMissingCount = result.mappingMissing().size();
        long noRawEventCount = result.unmatchedTimetables().size();
        long extraRawEventCount = result.unmatchedEvents().size();

        subwayDataService.saveAllMatchIssues(issues);

        log.info("[PhaseB] serviceDate={} MAPPING_MISSING={} NO_RAW_EVENT={} EXTRA_RAW_EVENT={} total={}",
                serviceDate, mappingMissingCount, noRawEventCount, extraRawEventCount, issues.size());

        return issues.size();
    }
}
