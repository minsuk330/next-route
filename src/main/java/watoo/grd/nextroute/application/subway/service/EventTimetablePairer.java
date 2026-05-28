package watoo.grd.nextroute.application.subway.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEvent;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.entity.SubwayTimetable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * subway_arrival_event ↔ subway_timetable 의 그룹핑/정렬/min-pair 페어링 루틴.
 *
 * <p>이 컴포넌트는 <b>주어진 입력을 그대로 페어링만</b> 한다. 사전 필터링
 * (scheduledArrivalAt == null 등) 정책은 <b>호출자</b>가 입력 단계에서 정한다:
 * <ul>
 *   <li>{@link TimetableMatchingService} — raw 입력 전달(기존 동작 보존,
 *       NO/EXTRA/MAPPING_MISSING 진단 생성)</li>
 *   <li>delay-truth 생성 서비스 — 사전 필터링한 입력 전달(C1) 후
 *       {@link PairingResult#matched()} 소비</li>
 * </ul>
 * {@code invalidTimetables}/{@code invalidEvents}는 페어링 결과를 바꾸지 않는
 * <b>정보성</b> bucket이다(루틴이 받은 입력 중 부적합 항목 표시).
 */
@Component
public class EventTimetablePairer {

    private final TimetableConverter converter;

    public EventTimetablePairer(TimetableConverter converter) {
        this.converter = converter;
    }

    public record CompareKey(String lineId, String stationId, String directionUD) {}

    public record TimetableEntry(SubwayTimetable timetable, LocalDateTime scheduledArrivalAt,
                                 String scheduledTimeSource, long orderKey) {}

    public record EventEntry(SubwayArrivalEvent event, long orderKey) {}

    /** event ↔ timetable 성공 페어 (truth 라벨 소스) */
    public record MatchedPair(CompareKey key, SubwayStation station, String matchGroupKey,
                              EventEntry event, TimetableEntry timetable,
                              int eventOrderIndex, int timetableOrderIndex,
                              int timetableCount, int eventCount) {}

    /** 시간표에는 있으나 이벤트가 없음 → NO_RAW_EVENT */
    public record UnmatchedTimetable(CompareKey key, SubwayStation station, String matchGroupKey,
                                     TimetableEntry timetable, int orderIndex,
                                     int timetableCount, int eventCount) {}

    /** 이벤트에는 있으나 시간표가 없음 → EXTRA_RAW_EVENT */
    public record UnmatchedEvent(CompareKey key, SubwayStation station, String matchGroupKey,
                                 EventEntry event, int orderIndex,
                                 int timetableCount, int eventCount) {}

    /** station 매핑 없음 (tago_station_id 부재) → MAPPING_MISSING */
    public record MappingMissing(CompareKey key, String matchGroupKey,
                                 SubwayArrivalEvent event, int eventIndex, int eventCount) {}

    /** 부적합 항목 (정보성, 페어링 결과 불변) */
    public record InvalidEvent(SubwayArrivalEvent event, String reason) {}
    public record InvalidTimetable(SubwayTimetable timetable, String reason) {}

    public record PairingResult(List<MatchedPair> matched,
                                List<UnmatchedTimetable> unmatchedTimetables,
                                List<UnmatchedEvent> unmatchedEvents,
                                List<MappingMissing> mappingMissing,
                                List<InvalidEvent> invalidEvents,
                                List<InvalidTimetable> invalidTimetables) {}

    /**
     * 기존 {@code TimetableMatchingService.matchForDate}의 Step 3~7 그룹핑/페어링
     * 로직을 동작 보존하여 추출한 것.
     */
    public PairingResult pair(LocalDate serviceDate, String dayType,
                              List<SubwayArrivalEvent> events,
                              List<SubwayStation> mappableStations,
                              List<SubwayTimetable> timetables) {

        List<MatchedPair> matched = new ArrayList<>();
        List<UnmatchedTimetable> unmatchedTimetables = new ArrayList<>();
        List<UnmatchedEvent> unmatchedEvents = new ArrayList<>();
        List<MappingMissing> mappingMissing = new ArrayList<>();
        List<InvalidEvent> invalidEvents = new ArrayList<>();
        List<InvalidTimetable> invalidTimetables = new ArrayList<>();

        // events → CompareKey 그룹핑 (direction 변환 실패는 invalidEvents)
        Map<CompareKey, List<SubwayArrivalEvent>> eventsGrouped = new HashMap<>();
        for (SubwayArrivalEvent ev : events) {
            String dirUD = converter.toTimetableDirection(ev.getDirection());
            if (dirUD == null) {
                invalidEvents.add(new InvalidEvent(ev, "DIRECTION_CONVERSION_FAILED"));
                continue;
            }
            CompareKey key = new CompareKey(ev.getLineId(), ev.getStationId(), dirUD);
            eventsGrouped.computeIfAbsent(key, k -> new ArrayList<>()).add(ev);
        }

        // lineIds: mappableStations + events
        Set<String> lineIds = new HashSet<>();
        for (SubwayStation st : mappableStations) lineIds.add(st.getLineId());
        for (SubwayArrivalEvent ev : events) lineIds.add(ev.getLineId());

        // stationByStationKey: (stationId + lineId) → SubwayStation
        Map<String, SubwayStation> stationByStationKey = new HashMap<>();
        for (SubwayStation st : mappableStations) {
            stationByStationKey.put(st.getStationId() + "_" + st.getLineId(), st);
        }

        // timetables → CompareKey(lineId, tagoStationId, direction) 기준 맵
        Map<CompareKey, List<SubwayTimetable>> timetableByKey = new HashMap<>();
        for (SubwayTimetable tt : timetables) {
            if (tt.getLineId() == null || tt.getTagoStationId() == null || tt.getDirection() == null) {
                invalidTimetables.add(new InvalidTimetable(tt, "NULL_KEY_FIELD"));
                continue;
            }
            timetableByKey.computeIfAbsent(
                new CompareKey(tt.getLineId(), tt.getTagoStationId(), tt.getDirection()),
                k -> new ArrayList<>()
            ).add(tt);
        }

        // timetableKeys: mappable station × {U,D} 중 timetable 존재 조합만 (stationId 기준 키로 환산)
        Set<CompareKey> timetableKeys = new HashSet<>();
        for (SubwayStation st : mappableStations) {
            for (String dir : new String[]{"U", "D"}) {
                CompareKey tagoKey = new CompareKey(st.getLineId(), st.getTagoStationId(), dir);
                if (timetableByKey.containsKey(tagoKey)) {
                    timetableKeys.add(new CompareKey(st.getLineId(), st.getStationId(), dir));
                }
            }
        }

        Set<CompareKey> compareKeys = new HashSet<>();
        compareKeys.addAll(eventsGrouped.keySet());
        compareKeys.addAll(timetableKeys);

        for (CompareKey key : compareKeys) {
            String matchGroupKey = serviceDate + "|" + key.lineId() + "|" + key.stationId()
                    + "|" + dayType + "|" + key.directionUD();

            List<SubwayArrivalEvent> keyEvents = eventsGrouped.getOrDefault(key, List.of());

            // a) station 매핑 없음 → MAPPING_MISSING (event 있을 때만)
            SubwayStation station = stationByStationKey.get(key.stationId() + "_" + key.lineId());
            if (station == null) {
                for (int i = 0; i < keyEvents.size(); i++) {
                    mappingMissing.add(new MappingMissing(key, matchGroupKey,
                            keyEvents.get(i), i, keyEvents.size()));
                }
                continue;
            }

            // b) timetable 조회 (tagoStationId 기준)
            CompareKey tagoKey = new CompareKey(key.lineId(), station.getTagoStationId(), key.directionUD());
            List<SubwayTimetable> rawTimetables = timetableByKey.getOrDefault(tagoKey, List.of());

            // c) TimetableEntry / EventEntry 생성
            List<TimetableEntry> ttEntries = new ArrayList<>();
            for (SubwayTimetable tt : rawTimetables) {
                LocalDateTime scheduledArrivalAt =
                        converter.toScheduledArrivalAt(serviceDate, tt.getArrTime(), tt.getDepTime());
                String scheduledTimeSource = converter.sourceOf(tt.getArrTime()).name();
                long orderKey = converter.toTimetableOrderKey(serviceDate, tt.getArrTime(), tt.getDepTime());
                ttEntries.add(new TimetableEntry(tt, scheduledArrivalAt, scheduledTimeSource, orderKey));
            }
            List<EventEntry> evEntries = new ArrayList<>();
            for (SubwayArrivalEvent ev : keyEvents) {
                long orderKey = converter.toEventOrderKey(serviceDate, ev.getArrivedAt());
                evEntries.add(new EventEntry(ev, orderKey));
            }

            // d) orderKey ASC 정렬
            ttEntries.sort(Comparator.comparingLong(TimetableEntry::orderKey));
            evEntries.sort(Comparator.comparingLong(EventEntry::orderKey));

            int timetableCount = ttEntries.size();
            int eventCount = evEntries.size();
            int matchPairs = Math.min(timetableCount, eventCount);

            // e) matched 페어
            for (int i = 0; i < matchPairs; i++) {
                matched.add(new MatchedPair(key, station, matchGroupKey,
                        evEntries.get(i), ttEntries.get(i),
                        i, i, timetableCount, eventCount));
            }

            // f) NO_RAW_EVENT (시간표 잔여)
            for (int i = matchPairs; i < timetableCount; i++) {
                unmatchedTimetables.add(new UnmatchedTimetable(key, station, matchGroupKey,
                        ttEntries.get(i), i, timetableCount, eventCount));
            }

            // g) EXTRA_RAW_EVENT (이벤트 잔여)
            for (int i = matchPairs; i < eventCount; i++) {
                unmatchedEvents.add(new UnmatchedEvent(key, station, matchGroupKey,
                        evEntries.get(i), i, timetableCount, eventCount));
            }
        }

        return new PairingResult(matched, unmatchedTimetables, unmatchedEvents,
                mappingMissing, invalidEvents, invalidTimetables);
    }
}
