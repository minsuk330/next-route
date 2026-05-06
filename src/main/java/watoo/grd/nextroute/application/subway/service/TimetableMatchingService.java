package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.subway.entity.MatchIssueType;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEvent;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEventMatchIssue;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.entity.SubwayTimetable;
import watoo.grd.nextroute.domain.subway.repository.SubwayTimetableRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayTimetableRepository.TimetableCoverageProjection;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.stream.Collectors.toList;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimetableMatchingService {

    private final SubwayDataService subwayDataService;
    private final TimetableConverter converter;
    private final SubwayTimetableRepository subwayTimetableRepository;

    private record CompareKey(String lineId, String stationId, String directionUD) {}

    private record TimetableEntry(SubwayTimetable timetable, LocalDateTime scheduledArrivalAt,
                                  String scheduledTimeSource, long orderKey) {}

    private record EventEntry(SubwayArrivalEvent event, long orderKey) {}

    @Transactional
    public int matchForDate(LocalDate serviceDate) {

        // Step 1: 이전 이슈 삭제
        subwayDataService.deleteMatchIssuesByServiceDate(serviceDate);

        // Step 2: 해당 날짜의 이벤트 조회
        List<SubwayArrivalEvent> events = subwayDataService.findArrivalEventsByServiceDate(serviceDate);

        // Step 3: events를 CompareKey로 그룹핑
        Map<CompareKey, List<SubwayArrivalEvent>> eventsGrouped = new HashMap<>();
        for (SubwayArrivalEvent ev : events) {
            String dirUD = converter.toTimetableDirection(ev.getDirection());
            if (dirUD == null) {
                log.warn("[PhaseB] direction 변환 실패, skip event id={} direction={}", ev.getId(), ev.getDirection());
                continue;
            }
            CompareKey key = new CompareKey(ev.getLineId(), ev.getStationId(), dirUD);
            eventsGrouped.computeIfAbsent(key, k -> new ArrayList<>()).add(ev);
        }

        // Step 4: dayType 계산
        String dayType = converter.toDayType(serviceDate);

        // Step 5: timetableCoverage를 CompareKey Set으로 변환
        List<TimetableCoverageProjection> coverage = subwayDataService.findTimetableCoverage(dayType);
        Set<CompareKey> timetableKeys = new HashSet<>();
        for (TimetableCoverageProjection proj : coverage) {
            List<SubwayStation> stations = subwayDataService.findByLineIdAndTagoStationId(
                    proj.getLineId(), proj.getTagoStationId());
            if (stations.isEmpty()) {
                log.info("[PhaseB] timetable coverage 역방향 매핑 없음 lineId={} tagoStationId={}",
                        proj.getLineId(), proj.getTagoStationId());
                continue;
            }
            for (SubwayStation st : stations) {
                String dirUD = proj.getDirection();
                if (dirUD == null || (!dirUD.equals("U") && !dirUD.equals("D"))) {
                    log.warn("[PhaseB] timetable coverage direction 미지원값, skip lineId={} tagoStationId={} direction={}",
                        proj.getLineId(), proj.getTagoStationId(), dirUD);
                    continue;
                }
                timetableKeys.add(new CompareKey(proj.getLineId(), st.getStationId(), dirUD));
            }
        }

        // Step 6: compareKeys = union
        Set<CompareKey> compareKeys = new HashSet<>();
        compareKeys.addAll(eventsGrouped.keySet());
        compareKeys.addAll(timetableKeys);

        // Step 7: 각 compareKey 처리
        List<SubwayArrivalEventMatchIssue> issues = new ArrayList<>();

        for (CompareKey key : compareKeys) {
            String matchGroupKey = serviceDate + "|" + key.lineId() + "|" + key.stationId() + "|" + dayType + "|" + key.directionUD();

            // a) station 조회
            Optional<SubwayStation> stationOpt = subwayDataService.findByStationIdAndLineId(key.stationId(), key.lineId());
            if (stationOpt.isEmpty()) {
                List<SubwayArrivalEvent> keyEvents = eventsGrouped.getOrDefault(key, List.of());
                if (keyEvents.isEmpty()) {
                    log.info("[PhaseB] station 매핑 없음 + event 없음, skip key={}", matchGroupKey);
                    continue;
                }
                for (int i = 0; i < keyEvents.size(); i++) {
                    issues.add(mappingMissingIssue(serviceDate, dayType, matchGroupKey,
                            key.lineId(), key.stationId(), null, key.directionUD(),
                            keyEvents.get(i), 0, keyEvents.size(), i));
                }
                continue;
            }
            SubwayStation station = stationOpt.get();
            if (station.getTagoStationId() == null) {
                List<SubwayArrivalEvent> keyEvents = eventsGrouped.getOrDefault(key, List.of());
                if (keyEvents.isEmpty()) {
                    log.info("[PhaseB] tagoStationId null + event 없음, skip key={}", matchGroupKey);
                    continue;
                }
                for (int i = 0; i < keyEvents.size(); i++) {
                    issues.add(mappingMissingIssue(serviceDate, dayType, matchGroupKey,
                            key.lineId(), key.stationId(), station.getStationName(), key.directionUD(),
                            keyEvents.get(i), 0, keyEvents.size(), i));
                }
                continue;
            }

            // b) rawTimetables 조회 후 lineId 필터링
            List<SubwayTimetable> rawTimetables = subwayTimetableRepository
                    .findByTagoStationIdAndDayTypeAndDirection(station.getTagoStationId(), dayType, key.directionUD());
            rawTimetables = rawTimetables.stream()
                    .filter(t -> key.lineId().equals(t.getLineId()))
                    .collect(toList());

            // c) TimetableEntry 리스트 생성
            List<TimetableEntry> ttEntries = new ArrayList<>();
            for (SubwayTimetable tt : rawTimetables) {
                LocalDateTime scheduledArrivalAt = converter.toScheduledArrivalAt(serviceDate, tt.getArrTime(), tt.getDepTime());
                String scheduledTimeSource = converter.sourceOf(tt.getArrTime()).name();
                long orderKey = converter.toTimetableOrderKey(serviceDate, tt.getArrTime(), tt.getDepTime());
                ttEntries.add(new TimetableEntry(tt, scheduledArrivalAt, scheduledTimeSource, orderKey));
            }

            // d) EventEntry 리스트 생성
            List<SubwayArrivalEvent> keyEvents = eventsGrouped.getOrDefault(key, List.of());
            List<EventEntry> evEntries = new ArrayList<>();
            for (SubwayArrivalEvent ev : keyEvents) {
                long orderKey = converter.toEventOrderKey(serviceDate, ev.getArrivedAt());
                evEntries.add(new EventEntry(ev, orderKey));
            }

            // e) 양쪽 orderKey ASC 정렬
            ttEntries.sort(Comparator.comparingLong(TimetableEntry::orderKey));
            evEntries.sort(Comparator.comparingLong(EventEntry::orderKey));

            // f) matchPairs 계산
            int matchPairs = Math.min(ttEntries.size(), evEntries.size());

            int timetableCount = ttEntries.size();
            int eventCount = evEntries.size();

            // g) NO_RAW_EVENT: 시간표에는 있으나 이벤트가 없는 경우
            List<TimetableEntry> unmatchedTt = ttEntries.subList(matchPairs, ttEntries.size());
            for (int i = 0; i < unmatchedTt.size(); i++) {
                TimetableEntry tt = unmatchedTt.get(i);
                issues.add(SubwayArrivalEventMatchIssue.builder()
                        .serviceDate(serviceDate)
                        .issueType(MatchIssueType.NO_RAW_EVENT.name())
                        .lineId(key.lineId())
                        .stationId(key.stationId())
                        .stationName(station.getStationName())
                        .direction(key.directionUD())
                        .dayType(dayType)
                        .matchGroupKey(matchGroupKey)
                        .timetableId(tt.timetable().getId())
                        .scheduledArrivalAt(tt.scheduledArrivalAt())
                        .scheduledTimeSource(tt.scheduledTimeSource())
                        .timetableOrderIndex(matchPairs + i)
                        .timetableCount(timetableCount)
                        .eventCount(eventCount)
                        .build());
            }

            // h) EXTRA_RAW_EVENT: 이벤트에는 있으나 시간표가 없는 경우
            List<EventEntry> unmatchedEv = evEntries.subList(matchPairs, evEntries.size());
            for (int i = 0; i < unmatchedEv.size(); i++) {
                EventEntry ev = unmatchedEv.get(i);
                issues.add(SubwayArrivalEventMatchIssue.builder()
                        .serviceDate(serviceDate)
                        .issueType(MatchIssueType.EXTRA_RAW_EVENT.name())
                        .lineId(key.lineId())
                        .stationId(key.stationId())
                        .stationName(station.getStationName())
                        .direction(key.directionUD())
                        .dayType(dayType)
                        .matchGroupKey(matchGroupKey)
                        .arrivalEventId(ev.event().getId())
                        .actualArrivedAt(ev.event().getArrivedAt())
                        .eventOrderIndex(matchPairs + i)
                        .timetableCount(timetableCount)
                        .eventCount(eventCount)
                        .build());
            }
        }

        // Step 8: 저장 및 로깅
        long mappingMissingCount = issues.stream()
                .filter(i -> MatchIssueType.MAPPING_MISSING.name().equals(i.getIssueType()))
                .count();
        long noRawEventCount = issues.stream()
                .filter(i -> MatchIssueType.NO_RAW_EVENT.name().equals(i.getIssueType()))
                .count();
        long extraRawEventCount = issues.stream()
                .filter(i -> MatchIssueType.EXTRA_RAW_EVENT.name().equals(i.getIssueType()))
                .count();

        subwayDataService.saveAllMatchIssues(issues);

        log.info("[PhaseB] serviceDate={} MAPPING_MISSING={} NO_RAW_EVENT={} EXTRA_RAW_EVENT={} total={}",
                serviceDate, mappingMissingCount, noRawEventCount, extraRawEventCount, issues.size());

        return issues.size();
    }

    private SubwayArrivalEventMatchIssue mappingMissingIssue(
            LocalDate serviceDate, String dayType, String matchGroupKey,
            String lineId, String stationId, String stationName,
            String directionUD, SubwayArrivalEvent event,
            int timetableCount, int eventCount, int eventIndex) {
        return SubwayArrivalEventMatchIssue.builder()
                .serviceDate(serviceDate)
                .issueType(MatchIssueType.MAPPING_MISSING.name())
                .lineId(lineId)
                .stationId(stationId)
                .stationName(stationName)
                .direction(directionUD)
                .dayType(dayType)
                .matchGroupKey(matchGroupKey)
                .arrivalEventId(event.getId())
                .actualArrivedAt(event.getArrivedAt())
                .eventOrderIndex(eventIndex)
                .timetableCount(timetableCount)
                .eventCount(eventCount)
                .build();
    }
}
