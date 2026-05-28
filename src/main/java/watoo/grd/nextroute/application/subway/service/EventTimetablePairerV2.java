package watoo.grd.nextroute.application.subway.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairer.CompareKey;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairer.EventEntry;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairer.InvalidEvent;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairer.InvalidTimetable;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairer.MappingMissing;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairer.MatchedPair;
import watoo.grd.nextroute.application.subway.service.EventTimetablePairer.TimetableEntry;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEvent;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.entity.SubwayTimetable;

import java.time.Duration;
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
 * V2 페어링 — count guard + time-window guard + destination hard reject 적용.
 *
 * <p>차이점 vs {@link EventTimetablePairer} (V1):
 * <ul>
 *   <li>{@code event_count != timetable_count} 그룹은 ordinal 페어 생성 금지 → {@link CountMismatchGroup}</li>
 *   <li>count equal 그룹은 ordinal 페어 생성 후 known-known destination mismatch 검사 → {@link DestinationMismatchGroup} (group-level reject)</li>
 *   <li>destination 통과 후 시간창(|delay| > maxMatchDistanceSeconds) 검사 → {@link RejectedByTimeDistanceGroup} (group-level reject)</li>
 *   <li>모든 pair 통과 시에만 matched로 확정</li>
 *   <li>rejected 그룹은 truth row를 만들지 않고 issue로만 진단된다 (호출자 책임)</li>
 * </ul>
 *
 * <p>참고: {@link MatchedPair#destination_match} 메타는 본 결과에 컬럼으로 두지 않는다.
 * 추후 분석은 {@code destination_name} / {@code end_station_name} 비교로 도출한다.
 */
@Component
public class EventTimetablePairerV2 {

    private final TimetableConverter converter;
    private final DestinationNormalizer destinationNormalizer;

    public EventTimetablePairerV2(TimetableConverter converter,
                                  DestinationNormalizer destinationNormalizer) {
        this.converter = converter;
        this.destinationNormalizer = destinationNormalizer;
    }

    /** count mismatch — Phase C 보강 후보 또는 진단 */
    public record CountMismatchGroup(CompareKey key, SubwayStation station, String matchGroupKey,
                                     int timetableCount, int eventCount,
                                     List<TimetableEntry> timetables,
                                     List<EventEntry> events) {}

    /** time-window 초과로 group-level reject */
    public record RejectedByTimeDistanceGroup(CompareKey key, SubwayStation station, String matchGroupKey,
                                              int timetableCount, int eventCount,
                                              List<MatchedPair> rejectedPairs,
                                              long maxAbsDelaySeconds) {}

    /** known-known destination mismatch로 group-level reject */
    public record DestinationMismatchGroup(CompareKey key, SubwayStation station, String matchGroupKey,
                                           int timetableCount, int eventCount,
                                           List<MatchedPair> rejectedPairs) {}

    public record PairingResult(List<MatchedPair> matched,
                                List<RejectedByTimeDistanceGroup> rejectedByTimeDistance,
                                List<DestinationMismatchGroup> destinationMismatch,
                                List<CountMismatchGroup> countMismatch,
                                List<MappingMissing> mappingMissing,
                                List<InvalidEvent> invalidEvents,
                                List<InvalidTimetable> invalidTimetables) {}

    public PairingResult pair(LocalDate serviceDate, String dayType,
                              List<SubwayArrivalEvent> events,
                              List<SubwayStation> mappableStations,
                              List<SubwayTimetable> timetables,
                              long maxMatchDistanceSeconds) {

        List<MatchedPair> matched = new ArrayList<>();
        List<RejectedByTimeDistanceGroup> rejectedByTimeDistance = new ArrayList<>();
        List<DestinationMismatchGroup> destinationMismatch = new ArrayList<>();
        List<CountMismatchGroup> countMismatch = new ArrayList<>();
        List<MappingMissing> mappingMissing = new ArrayList<>();
        List<InvalidEvent> invalidEvents = new ArrayList<>();
        List<InvalidTimetable> invalidTimetables = new ArrayList<>();

        // ── 그룹핑 (V1과 동일) ────────────────────────────────────────────────
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

        Map<String, SubwayStation> stationByStationKey = new HashMap<>();
        for (SubwayStation st : mappableStations) {
            stationByStationKey.put(st.getStationId() + "_" + st.getLineId(), st);
        }

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

            SubwayStation station = stationByStationKey.get(key.stationId() + "_" + key.lineId());
            if (station == null) {
                for (int i = 0; i < keyEvents.size(); i++) {
                    mappingMissing.add(new MappingMissing(key, matchGroupKey,
                            keyEvents.get(i), i, keyEvents.size()));
                }
                continue;
            }

            CompareKey tagoKey = new CompareKey(key.lineId(), station.getTagoStationId(), key.directionUD());
            List<SubwayTimetable> rawTimetables = timetableByKey.getOrDefault(tagoKey, List.of());

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

            ttEntries.sort(Comparator.comparingLong(TimetableEntry::orderKey));
            evEntries.sort(Comparator.comparingLong(EventEntry::orderKey));

            int timetableCount = ttEntries.size();
            int eventCount = evEntries.size();

            // ── V2 guard 1: count equal? ─────────────────────────────────────
            if (timetableCount != eventCount) {
                countMismatch.add(new CountMismatchGroup(key, station, matchGroupKey,
                        timetableCount, eventCount,
                        List.copyOf(ttEntries), List.copyOf(evEntries)));
                continue;
            }

            if (timetableCount == 0) {
                // 양쪽 다 비어있는 그룹 (compareKeys 보조 산출). 무시.
                continue;
            }

            // ── ordinal pair 생성 ───────────────────────────────────────────
            List<MatchedPair> pairs = new ArrayList<>(timetableCount);
            for (int i = 0; i < timetableCount; i++) {
                pairs.add(new MatchedPair(key, station, matchGroupKey,
                        evEntries.get(i), ttEntries.get(i),
                        i, i, timetableCount, eventCount));
            }

            // ── V2 guard 2: known-known destination mismatch? (group-level) ──
            boolean destReject = pairs.stream().anyMatch(p ->
                    destinationNormalizer.compare(
                            p.event().event().getDestinationName(),
                            p.timetable().timetable().getEndStationName()
                    ) == DestinationNormalizer.Match.KNOWN_MISMATCH);
            if (destReject) {
                destinationMismatch.add(new DestinationMismatchGroup(key, station, matchGroupKey,
                        timetableCount, eventCount, pairs));
                continue;
            }

            // ── V2 guard 3: time-window 초과? (group-level) ─────────────────
            long maxAbsDelay = 0L;
            boolean timeReject = false;
            for (MatchedPair p : pairs) {
                long delay = Math.abs(Duration.between(
                        p.timetable().scheduledArrivalAt(),
                        p.event().event().getArrivedAt()).getSeconds());
                if (delay > maxAbsDelay) maxAbsDelay = delay;
                if (delay > maxMatchDistanceSeconds) timeReject = true;
            }
            if (timeReject) {
                rejectedByTimeDistance.add(new RejectedByTimeDistanceGroup(
                        key, station, matchGroupKey, timetableCount, eventCount, pairs, maxAbsDelay));
                continue;
            }

            // ── 모든 guard 통과 → matched 확정 ──────────────────────────────
            matched.addAll(pairs);
        }

        return new PairingResult(matched, rejectedByTimeDistance, destinationMismatch,
                countMismatch, mappingMissing, invalidEvents, invalidTimetables);
    }
}
