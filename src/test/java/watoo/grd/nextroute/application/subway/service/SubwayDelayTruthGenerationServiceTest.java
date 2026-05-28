package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.domain.subway.entity.MlSubwayDelayTruth;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEvent;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.entity.SubwayTimetable;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubwayDelayTruthGenerationServiceTest {

    static class FakeHolidayCalendar implements HolidayCalendar {
        private final Set<LocalDate> holidays;
        FakeHolidayCalendar(LocalDate... dates) { this.holidays = Set.of(dates); }
        @Override public boolean isHoliday(LocalDate date) { return holidays.contains(date); }
    }

    @Mock SubwayDataService subwayDataService;

    SubwayDelayTruthGenerationService service;

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 5, 3); // 일요일 → dayType "03"

    @BeforeEach
    void setUp() {
        TimetableConverter converter = new TimetableConverter(new FakeHolidayCalendar());
        DestinationNormalizer destinationNormalizer = new DestinationNormalizer();
        service = new SubwayDelayTruthGenerationService(
                subwayDataService, converter,
                new EventTimetablePairer(converter),
                new EventTimetablePairerV2(converter, destinationNormalizer));
        service.matchingVersion = "v1";
        service.maxMatchDistanceSeconds = 1800L;
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private SubwayArrivalEvent event(String lineId, String stationId, String direction,
                                     String trainNo, LocalDateTime arrivedAt, String eventSource) {
        return SubwayArrivalEvent.builder()
                .serviceDate(SERVICE_DATE).lineId(lineId).stationId(stationId)
                .stationName("테스트역").direction(direction).trainNo(trainNo)
                .arrivedAt(arrivedAt).firstObservedAt(arrivedAt).lastObservedAt(arrivedAt)
                .rawCount(1).eventSource(eventSource).destinationKey("DK").build();
    }

    private SubwayArrivalEvent event(String lineId, String stationId, String direction,
                                     LocalDateTime arrivedAt) {
        return event(lineId, stationId, direction, "T" + arrivedAt.getHour(), arrivedAt, "OBSERVED_CODE_1");
    }

    private SubwayTimetable timetable(String lineId, String tagoStationId, String direction, String arrTime) {
        return SubwayTimetable.builder()
                .lineId(lineId).tagoStationId(tagoStationId).stationName("테스트역")
                .direction(direction).dayType("03").arrTime(arrTime).depTime(arrTime)
                .endStationName("종착역").build();
    }

    private SubwayStation station(String stationId, String lineId, String tagoStationId) {
        return SubwayStation.builder()
                .stationId(stationId).tagoStationId(tagoStationId)
                .stationName("테스트역").lineId(lineId).build();
    }

    private void stubInputs(List<SubwayArrivalEvent> events,
                            List<SubwayStation> stations,
                            List<SubwayTimetable> timetables) {
        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayDataService.findMappableStations()).thenReturn(stations);
        when(subwayDataService.findTimetablesByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(timetables);
        when(subwayDataService.saveAllDelayTruth(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private List<MlSubwayDelayTruth> captureSaved() {
        ArgumentCaptor<List<MlSubwayDelayTruth>> captor = ArgumentCaptor.forClass(List.class);
        verify(subwayDataService).saveAllDelayTruth(captor.capture());
        return captor.getValue();
    }

    // ── TC1: 1:1 매칭 → delay_seconds 산출·저장 ──────────────────────────────

    @Test
    void TC1_event1_timetable1이면_delay_seconds가_저장된다() {
        SubwayStation st = station("S1", "1002", "T1");
        SubwayTimetable tt = timetable("1002", "T1", "U", "100000");
        SubwayArrivalEvent ev = event("1002", "S1", "내선", LocalDateTime.of(2026, 5, 3, 10, 0, 30));

        stubInputs(List.of(ev), List.of(st), List.of(tt));

        service.generateForDate(SERVICE_DATE);

        List<MlSubwayDelayTruth> saved = captureSaved();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getDelaySeconds()).isEqualTo(30);
        assertThat(saved.get(0).getDirection()).isEqualTo("U");
        assertThat(saved.get(0).getMatchStrategy()).isEqualTo("ORDINAL");
        assertThat(saved.get(0).isExcludedFromTraining()).isFalse();
    }

    // ── TC2: 3:3 매칭 → 3개 순서대로 ─────────────────────────────────────────

    @Test
    void TC2_event3_timetable3이면_3개_순서대로_저장된다() {
        SubwayStation st = station("S1", "1002", "T1");
        List<SubwayArrivalEvent> events = List.of(
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 10, 0, 0)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 11, 0, 5)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 12, 0, 10)));
        List<SubwayTimetable> tts = List.of(
                timetable("1002", "T1", "U", "100000"),
                timetable("1002", "T1", "U", "110000"),
                timetable("1002", "T1", "U", "120000"));

        stubInputs(events, List.of(st), tts);
        service.generateForDate(SERVICE_DATE);

        List<MlSubwayDelayTruth> saved = captureSaved();
        assertThat(saved).hasSize(3);
        assertThat(saved).extracting(MlSubwayDelayTruth::getDelaySeconds)
                .containsExactlyInAnyOrder(0, 5, 10);
    }

    // ── TC3: event 5 / timetable 3 → 3개만 ───────────────────────────────────

    @Test
    void TC3_event5_timetable3이면_3개만_저장된다() {
        SubwayStation st = station("S1", "1002", "T1");
        List<SubwayArrivalEvent> events = List.of(
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 10, 0, 0)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 11, 0, 0)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 12, 0, 0)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 13, 0, 0)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 14, 0, 0)));
        List<SubwayTimetable> tts = List.of(
                timetable("1002", "T1", "U", "100000"),
                timetable("1002", "T1", "U", "110000"),
                timetable("1002", "T1", "U", "120000"));

        stubInputs(events, List.of(st), tts);
        service.generateForDate(SERVICE_DATE);

        assertThat(captureSaved()).hasSize(3);
    }

    // ── TC4: event 3 / timetable 5 → 3개만 ───────────────────────────────────

    @Test
    void TC4_event3_timetable5이면_3개만_저장된다() {
        SubwayStation st = station("S1", "1002", "T1");
        List<SubwayArrivalEvent> events = List.of(
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 10, 0, 0)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 11, 0, 0)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 12, 0, 0)));
        List<SubwayTimetable> tts = List.of(
                timetable("1002", "T1", "U", "100000"),
                timetable("1002", "T1", "U", "110000"),
                timetable("1002", "T1", "U", "120000"),
                timetable("1002", "T1", "U", "130000"),
                timetable("1002", "T1", "U", "140000"));

        stubInputs(events, List.of(st), tts);
        service.generateForDate(SERVICE_DATE);

        assertThat(captureSaved()).hasSize(3);
    }

    // ── TC5: direction 변환 ──────────────────────────────────────────────────

    @Test
    void TC5_내선과_외선이_U와_D로_저장된다() {
        SubwayStation st = station("S1", "1002", "T1");
        List<SubwayArrivalEvent> events = List.of(
                event("1002", "S1", "내선", LocalDateTime.of(2026, 5, 3, 10, 0, 0)),
                event("1002", "S1", "외선", LocalDateTime.of(2026, 5, 3, 10, 5, 0)));
        List<SubwayTimetable> tts = List.of(
                timetable("1002", "T1", "U", "100000"),
                timetable("1002", "T1", "D", "100500"));

        stubInputs(events, List.of(st), tts);
        service.generateForDate(SERVICE_DATE);

        List<MlSubwayDelayTruth> saved = captureSaved();
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(MlSubwayDelayTruth::getDirection)
                .containsExactlyInAnyOrder("U", "D");
    }

    // ── TC6: arrTime="0" → depTime − 30s, source = DEP_TIME_MINUS_30S_FOR_ZERO_ARRIVAL ─

    @Test
    void TC6_arrTime_0이면_depTime_minus_30s를_쓰고_source가_기록된다() {
        SubwayStation st = station("S1", "1002", "T1");
        // arrTime="0" + depTime="100100" → scheduled = 10:00:30
        SubwayTimetable tt = SubwayTimetable.builder()
                .lineId("1002").tagoStationId("T1").stationName("테스트역")
                .direction("U").dayType("03").arrTime("0").depTime("100100")
                .endStationName("종착역").build();
        // event arrived 10:00:30 → delay 0
        SubwayArrivalEvent ev = event("1002", "S1", "내선", LocalDateTime.of(2026, 5, 3, 10, 0, 30));

        stubInputs(List.of(ev), List.of(st), List.of(tt));
        service.generateForDate(SERVICE_DATE);

        List<MlSubwayDelayTruth> saved = captureSaved();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getScheduledArrivalAt()).isEqualTo(LocalDateTime.of(2026, 5, 3, 10, 0, 30));
        assertThat(saved.get(0).getDelaySeconds()).isEqualTo(0);
        assertThat(saved.get(0).getScheduledTimeSource()).isEqualTo("DEP_TIME_MINUS_30S_FOR_ZERO_ARRIVAL");
    }

    // ── TC7: |delay|>900s → row 저장되되 excluded_from_training=true ───────

    @Test
    void TC7_지연_900초_초과이면_저장되되_학습제외_OUTLIER_DELAY로_표기된다() {
        SubwayStation st = station("S1", "1002", "T1");
        SubwayTimetable tt = timetable("1002", "T1", "U", "100000");
        // 16분 지연 = 960s > 900
        SubwayArrivalEvent ev = event("1002", "S1", "내선", LocalDateTime.of(2026, 5, 3, 10, 16, 0));

        stubInputs(List.of(ev), List.of(st), List.of(tt));
        service.generateForDate(SERVICE_DATE);

        List<MlSubwayDelayTruth> saved = captureSaved();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getDelaySeconds()).isEqualTo(960);
        assertThat(saved.get(0).isExcludedFromTraining()).isTrue();
        assertThat(saved.get(0).getExcludeReason()).isEqualTo("OUTLIER_DELAY");
    }

    // ── TC8: 재실행 멱등 — delete 2번 호출 ────────────────────────────────

    @Test
    void TC8_같은날짜로_두번_호출하면_delete가_2번_호출된다() {
        SubwayStation st = station("S1", "1002", "T1");
        SubwayTimetable tt = timetable("1002", "T1", "U", "100000");
        SubwayArrivalEvent ev = event("1002", "S1", "내선", LocalDateTime.of(2026, 5, 3, 10, 0, 0));

        stubInputs(List.of(ev), List.of(st), List.of(tt));

        service.generateForDate(SERVICE_DATE);
        service.generateForDate(SERVICE_DATE);

        verify(subwayDataService, times(2)).deleteDelayTruthByServiceDate(SERVICE_DATE);
    }

    // ── TC9: 사전 필터 순서 — scheduledArrivalAt 산출 불가 timetable이 *페어링 전*에 제외 ──

    @Test
    void TC9_scheduledArrivalAt_산출_불가_timetable은_페어링_전에_제외된다() {
        SubwayStation st = station("S1", "1002", "T1");
        // arrTime="bad" → parseTime null → scheduledArrivalAt null → 사전 필터 대상
        SubwayTimetable badTt = SubwayTimetable.builder()
                .lineId("1002").tagoStationId("T1").stationName("테스트역")
                .direction("U").dayType("03").arrTime("bad").depTime("bad")
                .endStationName("종착역").build();
        SubwayTimetable goodTt1 = timetable("1002", "T1", "U", "100000");
        SubwayTimetable goodTt2 = timetable("1002", "T1", "U", "110000");

        List<SubwayArrivalEvent> events = List.of(
                event("1002", "S1", "내선", LocalDateTime.of(2026, 5, 3, 10, 0, 0)),
                event("1002", "S1", "내선", LocalDateTime.of(2026, 5, 3, 11, 0, 0)),
                event("1002", "S1", "내선", LocalDateTime.of(2026, 5, 3, 12, 0, 0)));

        stubInputs(events, List.of(st), List.of(goodTt1, badTt, goodTt2));
        service.generateForDate(SERVICE_DATE);

        List<MlSubwayDelayTruth> saved = captureSaved();
        // 사전 필터로 badTt 제외 → 유효 tt 2개 vs event 3개 → matched 2건
        // (만약 페어링 *후* 필터링이라면 matched가 3건이 되어 한 행이 null scheduled로 잘못 짝지어짐 — 회귀 가드)
        assertThat(saved).hasSize(2);
        assertThat(saved).allMatch(t -> t.getScheduledArrivalAt() != null);
    }

    // ── TC10: INFERRED_FROM_PREV_DEPARTURE event → excluded INFERRED_EVENT ─

    @Test
    void TC10_INFERRED_event는_저장되되_학습제외_INFERRED_EVENT로_표기된다() {
        SubwayStation st = station("S1", "1002", "T1");
        SubwayTimetable tt = timetable("1002", "T1", "U", "100000");
        SubwayArrivalEvent ev = event("1002", "S1", "내선", "T10",
                LocalDateTime.of(2026, 5, 3, 10, 0, 5),
                SubwayInferredArrivalCompletionService.EVENT_SOURCE);

        stubInputs(List.of(ev), List.of(st), List.of(tt));
        service.generateForDate(SERVICE_DATE);

        List<MlSubwayDelayTruth> saved = captureSaved();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).isExcludedFromTraining()).isTrue();
        assertThat(saved.get(0).getExcludeReason()).isEqualTo("INFERRED_EVENT");
        assertThat(saved.get(0).getEventSource()).isEqualTo("INFERRED_FROM_PREV_DEPARTURE");
    }

    // ── V2 ────────────────────────────────────────────────────────────────

    private SubwayTimetable timetable(String lineId, String tagoStationId, String direction,
                                      String arrTime, String endStationName) {
        return SubwayTimetable.builder()
                .lineId(lineId).tagoStationId(tagoStationId).stationName("테스트역")
                .direction(direction).dayType("03").arrTime(arrTime).depTime(arrTime)
                .endStationName(endStationName).build();
    }

    private SubwayArrivalEvent eventWithDestination(String stationId, String direction,
                                                    LocalDateTime arrivedAt, String destinationName) {
        return SubwayArrivalEvent.builder()
                .serviceDate(SERVICE_DATE).lineId("1002").stationId(stationId)
                .stationName("테스트역").direction(direction).trainNo("T1")
                .destinationName(destinationName)
                .arrivedAt(arrivedAt).firstObservedAt(arrivedAt).lastObservedAt(arrivedAt)
                .rawCount(1).eventSource("OBSERVED_CODE_1").destinationKey("DK").build();
    }

    @Test
    void TC_V2_happy_path_count_equal_within_window이면_matched_저장() {
        service.matchingVersion = "v2";
        SubwayStation st = station("S1", "1002", "T1");
        SubwayTimetable tt = timetable("1002", "T1", "U", "100000", "한강진");
        SubwayArrivalEvent ev = eventWithDestination("S1", "내선",
                LocalDateTime.of(2026, 5, 3, 10, 0, 30), "한강진");

        stubInputs(List.of(ev), List.of(st), List.of(tt));
        service.generateForDate(SERVICE_DATE);

        List<MlSubwayDelayTruth> saved = captureSaved();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getDelaySeconds()).isEqualTo(30);
        assertThat(saved.get(0).isExcludedFromTraining()).isFalse();
        assertThat(saved.get(0).getExcludeReason()).isNull();
    }

    @Test
    void TC_V2_time_window_초과_그룹은_truth_저장_안함() {
        service.matchingVersion = "v2";
        SubwayStation st = station("S1", "1002", "T1");
        SubwayTimetable tt = timetable("1002", "T1", "U", "054850", "한강진"); // 05:48:50
        // event = 다음날 00:54:02 → 19시간+ 차이 → group reject
        SubwayArrivalEvent ev = eventWithDestination("S1", "내선",
                LocalDateTime.of(2026, 5, 4, 0, 54, 2), "한강진");

        stubInputs(List.of(ev), List.of(st), List.of(tt));
        service.generateForDate(SERVICE_DATE);

        assertThat(captureSaved()).isEmpty();
    }

    @Test
    void TC_V2_known_known_destination_mismatch는_truth_저장_안함() {
        service.matchingVersion = "v2";
        SubwayStation st = station("S1", "1002", "T1");
        SubwayTimetable tt = timetable("1002", "T1", "U", "100000", "응암");
        SubwayArrivalEvent ev = eventWithDestination("S1", "내선",
                LocalDateTime.of(2026, 5, 3, 10, 0, 30), "한강진");

        stubInputs(List.of(ev), List.of(st), List.of(tt));
        service.generateForDate(SERVICE_DATE);

        assertThat(captureSaved()).isEmpty();
    }

    @Test
    void TC_V2_destination_unknown은_truth_저장된다() {
        service.matchingVersion = "v2";
        SubwayStation st = station("S1", "1002", "T1");
        SubwayTimetable tt = timetable("1002", "T1", "U", "100000", "한강진");
        SubwayArrivalEvent ev = eventWithDestination("S1", "내선",
                LocalDateTime.of(2026, 5, 3, 10, 0, 30), null); // destination 없음

        stubInputs(List.of(ev), List.of(st), List.of(tt));
        service.generateForDate(SERVICE_DATE);

        assertThat(captureSaved()).hasSize(1);
    }

    @Test
    void TC_V2_count_mismatch는_truth_저장_안함() {
        service.matchingVersion = "v2";
        SubwayStation st = station("S1", "1002", "T1");
        List<SubwayTimetable> tts = List.of(
                timetable("1002", "T1", "U", "100000", "한강진"),
                timetable("1002", "T1", "U", "110000", "한강진")); // 2 timetable vs 1 event

        List<SubwayArrivalEvent> events = List.of(
                eventWithDestination("S1", "내선", LocalDateTime.of(2026, 5, 3, 10, 0, 30), "한강진"));

        stubInputs(events, List.of(st), tts);
        service.generateForDate(SERVICE_DATE);

        assertThat(captureSaved()).isEmpty();
    }

    @Test
    void TC_V2_INFERRED_event는_저장되되_학습제외() {
        service.matchingVersion = "v2";
        SubwayStation st = station("S1", "1002", "T1");
        SubwayTimetable tt = timetable("1002", "T1", "U", "100000", "한강진");
        SubwayArrivalEvent ev = SubwayArrivalEvent.builder()
                .serviceDate(SERVICE_DATE).lineId("1002").stationId("S1")
                .stationName("테스트역").direction("내선").trainNo("T1")
                .destinationName("한강진")
                .arrivedAt(LocalDateTime.of(2026, 5, 3, 10, 0, 5))
                .firstObservedAt(LocalDateTime.of(2026, 5, 3, 10, 0, 5))
                .lastObservedAt(LocalDateTime.of(2026, 5, 3, 10, 0, 5))
                .rawCount(1)
                .eventSource(SubwayInferredArrivalCompletionService.EVENT_SOURCE)
                .destinationKey("DK").build();

        stubInputs(List.of(ev), List.of(st), List.of(tt));
        service.generateForDate(SERVICE_DATE);

        List<MlSubwayDelayTruth> saved = captureSaved();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).isExcludedFromTraining()).isTrue();
        assertThat(saved.get(0).getExcludeReason()).isEqualTo("INFERRED_EVENT");
    }
}
