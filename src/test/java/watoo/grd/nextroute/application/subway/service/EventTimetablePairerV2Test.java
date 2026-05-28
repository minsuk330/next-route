package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEvent;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.entity.SubwayTimetable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EventTimetablePairerV2Test {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 5, 3); // 일요일 → dayType "03"
    private static final long MAX_DISTANCE = 1800L;

    static class FakeHolidayCalendar implements HolidayCalendar {
        private final Set<LocalDate> holidays;
        FakeHolidayCalendar(LocalDate... dates) { this.holidays = Set.of(dates); }
        @Override public boolean isHoliday(LocalDate date) { return holidays.contains(date); }
    }

    private final TimetableConverter converter = new TimetableConverter(new FakeHolidayCalendar());
    private final DestinationNormalizer destinationNormalizer = new DestinationNormalizer();
    private final EventTimetablePairerV2 pairer = new EventTimetablePairerV2(converter, destinationNormalizer);

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private SubwayArrivalEvent event(String stationId, String direction, String trainNo,
                                     LocalDateTime arrivedAt, String destinationName, String eventSource) {
        return SubwayArrivalEvent.builder()
                .serviceDate(SERVICE_DATE).lineId("1006").stationId(stationId)
                .stationName("테스트역").direction(direction).trainNo(trainNo)
                .destinationName(destinationName)
                .arrivedAt(arrivedAt).firstObservedAt(arrivedAt).lastObservedAt(arrivedAt)
                .rawCount(1).eventSource(eventSource).destinationKey("DK").build();
    }

    private SubwayArrivalEvent observed(String stationId, String direction, String trainNo,
                                        LocalDateTime arrivedAt, String destinationName) {
        return event(stationId, direction, trainNo, arrivedAt, destinationName, "OBSERVED_CODE_1");
    }

    private SubwayTimetable timetable(String tagoStationId, String direction, String arrTime, String endStationName) {
        return SubwayTimetable.builder()
                .lineId("1006").tagoStationId(tagoStationId).stationName("테스트역")
                .direction(direction).dayType("03").arrTime(arrTime).depTime(arrTime)
                .endStationName(endStationName).build();
    }

    private SubwayStation station() {
        return SubwayStation.builder()
                .stationId("S1").tagoStationId("T1")
                .stationName("테스트역").lineId("1006").build();
    }

    private EventTimetablePairerV2.PairingResult pair(List<SubwayArrivalEvent> events,
                                                     List<SubwayTimetable> timetables) {
        return pairer.pair(SERVICE_DATE, "03", events, List.of(station()), timetables, MAX_DISTANCE);
    }

    // ── TC1: count equal + 시간창 내 → matched ───────────────────────────────

    @Test
    void TC1_count_equal_pairs_within_window_become_matched() {
        var ev = observed("S1", "내선", "T1", LocalDateTime.of(2026, 5, 3, 10, 0, 30), "한강진");
        var tt = timetable("T1", "U", "100000", "한강진");

        var result = pair(List.of(ev), List.of(tt));

        assertThat(result.matched()).hasSize(1);
        assertThat(result.rejectedByTimeDistance()).isEmpty();
        assertThat(result.destinationMismatch()).isEmpty();
        assertThat(result.countMismatch()).isEmpty();
    }

    // ── TC2: count equal + 1 pair가 30분 초과 → group reject ─────────────────

    @Test
    void TC2_count_equal_one_pair_over_window_rejects_group() {
        var ev1 = observed("S1", "내선", "T1", LocalDateTime.of(2026, 5, 3, 10, 0, 0), "한강진");
        var ev2 = observed("S1", "내선", "T2", LocalDateTime.of(2026, 5, 4, 0, 54, 0), "한강진"); // ↔ 05:48 시간표 ⇒ 19h+
        var tt1 = timetable("T1", "U", "100000", "한강진");
        var tt2 = timetable("T1", "U", "054800", "한강진");

        var result = pair(List.of(ev1, ev2), List.of(tt1, tt2));

        assertThat(result.matched()).isEmpty();
        assertThat(result.rejectedByTimeDistance()).hasSize(1);
        var rejected = result.rejectedByTimeDistance().get(0);
        assertThat(rejected.worstPair()).isNotNull();
        assertThat(rejected.allAbsDelaysSeconds()).hasSize(2);
        assertThat(rejected.truncated()).isFalse();
        assertThat(rejected.maxAbsDelaySeconds()).isGreaterThan(MAX_DISTANCE);
        // worstPair는 |delay|가 가장 큰 pair여야 한다 (05:48 시간표 ↔ 다음날 00:54 이벤트)
        assertThat(Math.abs(java.time.Duration.between(
                rejected.worstPair().timetable().scheduledArrivalAt(),
                rejected.worstPair().event().event().getArrivedAt()).getSeconds()))
                .isEqualTo(rejected.maxAbsDelaySeconds());
    }

    // ── TC3: known-known destination mismatch → group reject ─────────────────

    @Test
    void TC3_known_known_destination_mismatch_rejects_group() {
        var ev = observed("S1", "내선", "T1", LocalDateTime.of(2026, 5, 3, 10, 0, 30), "한강진");
        var tt = timetable("T1", "U", "100000", "응암");

        var result = pair(List.of(ev), List.of(tt));

        assertThat(result.matched()).isEmpty();
        assertThat(result.destinationMismatch()).hasSize(1);
        var dm = result.destinationMismatch().get(0);
        assertThat(dm.firstMismatchPair()).isNotNull();
        assertThat(dm.mismatchDestinations()).hasSize(1);
        assertThat(dm.mismatchDestinations().get(0).eventDestination()).isEqualTo("한강진");
        assertThat(dm.mismatchDestinations().get(0).timetableEndStation()).isEqualTo("응암");
        assertThat(dm.truncated()).isFalse();
    }

    // ── TC4: destination unknown + 시간창 내 → matched (unknown 보호) ─────

    @Test
    void TC4_destination_unknown_with_window_pass_becomes_matched() {
        var ev = observed("S1", "내선", "T1", LocalDateTime.of(2026, 5, 3, 10, 0, 30), null);
        var tt = timetable("T1", "U", "100000", "한강진");

        var result = pair(List.of(ev), List.of(tt));

        assertThat(result.matched()).hasSize(1);
        assertThat(result.destinationMismatch()).isEmpty();
    }

    // ── TC5: event_count < timetable_count → COUNT_MISMATCH (Phase C 대상) ──

    @Test
    void TC5_fewer_events_than_timetables_becomes_count_mismatch() {
        var ev = observed("S1", "내선", "T1", LocalDateTime.of(2026, 5, 3, 10, 0, 0), "한강진");
        var tt1 = timetable("T1", "U", "100000", "한강진");
        var tt2 = timetable("T1", "U", "100500", "한강진");

        var result = pair(List.of(ev), List.of(tt1, tt2));

        assertThat(result.matched()).isEmpty();
        assertThat(result.rejectedByTimeDistance()).isEmpty();
        assertThat(result.countMismatch()).hasSize(1);
        var g = result.countMismatch().get(0);
        assertThat(g.timetableCount()).isEqualTo(2);
        assertThat(g.eventCount()).isEqualTo(1);
    }

    // ── TC6: event_count > timetable_count → COUNT_MISMATCH (보강 대상 아님) ─

    @Test
    void TC6_more_events_than_timetables_becomes_count_mismatch_extra() {
        var ev1 = observed("S1", "내선", "T1", LocalDateTime.of(2026, 5, 3, 10, 0, 0), "한강진");
        var ev2 = observed("S1", "내선", "T2", LocalDateTime.of(2026, 5, 3, 10, 5, 0), "한강진");
        var tt = timetable("T1", "U", "100000", "한강진");

        var result = pair(List.of(ev1, ev2), List.of(tt));

        assertThat(result.matched()).isEmpty();
        assertThat(result.countMismatch()).hasSize(1);
        var g = result.countMismatch().get(0);
        assertThat(g.timetableCount()).isEqualTo(1);
        assertThat(g.eventCount()).isEqualTo(2);
    }

    // ── TC7: 심야 이벤트 vs 첫차 시간표 (count 1:1) → time-window reject ──────

    @Test
    void TC7_midnight_event_does_not_match_first_train_when_window_fails() {
        // 시간표: serviceDate 다음날 00:51:10 (midnight timetable)
        var tt = timetable("T1", "U", "054850", "한강진"); // 05:48:50 첫차
        // event: serviceDate+1 00:54:02 → 19h+ 차이 → reject
        var ev = observed("S1", "내선", "T1",
                LocalDateTime.of(2026, 5, 4, 0, 54, 2), "한강진");

        var result = pair(List.of(ev), List.of(tt));

        assertThat(result.matched()).isEmpty();
        assertThat(result.rejectedByTimeDistance()).hasSize(1);
    }

    // ── TC8: midnight timetable 005110 → serviceDate + 1 day 00:51:10 매핑 ──

    @Test
    void TC8_midnight_timetable_005110_matches_midnight_event_within_window() {
        // 시간표: serviceDate+1 00:51:10
        var tt = timetable("T1", "U", "005110", "한강진");
        // event: serviceDate+1 00:54:02 → ~172초 차이 → matched
        var ev = observed("S1", "내선", "T1",
                LocalDateTime.of(2026, 5, 4, 0, 54, 2), "한강진");

        var result = pair(List.of(ev), List.of(tt));

        assertThat(result.matched()).hasSize(1);
        int delay = (int) java.time.Duration.between(
                result.matched().get(0).timetable().scheduledArrivalAt(),
                result.matched().get(0).event().event().getArrivedAt()).getSeconds();
        assertThat(Math.abs(delay)).isLessThan((int) MAX_DISTANCE);
    }

    // ── TC9: 모든 pair가 window 통과 + count equal → matched ─────────────────

    @Test
    void TC9_count_equal_all_pairs_within_window_all_matched() {
        var ev1 = observed("S1", "내선", "T1", LocalDateTime.of(2026, 5, 3, 10, 0, 30), "한강진");
        var ev2 = observed("S1", "내선", "T2", LocalDateTime.of(2026, 5, 3, 10, 5, 30), "한강진");
        var tt1 = timetable("T1", "U", "100000", "한강진");
        var tt2 = timetable("T1", "U", "100500", "한강진");

        var result = pair(List.of(ev1, ev2), List.of(tt1, tt2));

        assertThat(result.matched()).hasSize(2);
    }

    // ── TC10: trace 배열 cap (MAX_TRACE_ITEMS 초과) → truncated=true ───────

    @Test
    void TC10_time_distance_trace는_MAX_TRACE_ITEMS_초과시_truncated_true() {
        int n = EventTimetablePairerV2.MAX_TRACE_ITEMS + 5; // 105 pair
        List<SubwayArrivalEvent> events = new java.util.ArrayList<>();
        List<SubwayTimetable> tts = new java.util.ArrayList<>();
        // 모든 pair가 19h+ 차이 (시간표 05:48 vs 이벤트 다음날 00:54 변형)
        for (int i = 0; i < n; i++) {
            // 시간표: 05:48 + i초 (정렬 순서 보장)
            String arr = String.format("0548%02d", i % 60); // 05:48:XX
            tts.add(timetable("T1", "U", arr, "한강진"));
            // 이벤트: 다음날 00:54:XX
            events.add(observed("S1", "내선", "T" + i,
                    LocalDateTime.of(2026, 5, 4, 0, 54, i % 60), "한강진"));
        }

        var result = pair(events, tts);

        assertThat(result.matched()).isEmpty();
        assertThat(result.rejectedByTimeDistance()).hasSize(1);
        var g = result.rejectedByTimeDistance().get(0);
        assertThat(g.allAbsDelaysSeconds()).hasSize(EventTimetablePairerV2.MAX_TRACE_ITEMS);
        assertThat(g.truncated()).isTrue();
        assertThat(g.timetableCount()).isEqualTo(n); // 실제 pair 수는 보존
    }
}
