package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.domain.subway.entity.MatchIssueType;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEvent;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEventMatchIssue;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TimetableMatchingServiceTest {

    static class FakeHolidayCalendar implements HolidayCalendar {
        private final Set<LocalDate> holidays;
        FakeHolidayCalendar(LocalDate... dates) { this.holidays = Set.of(dates); }
        @Override public boolean isHoliday(LocalDate date) { return holidays.contains(date); }
    }

    @Mock SubwayDataService subwayDataService;

    TimetableMatchingService service;

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 5, 3);

    @BeforeEach
    void setUp() {
        TimetableConverter converter = new TimetableConverter(
                new FakeHolidayCalendar(LocalDate.of(2026, 5, 5)));
        service = new TimetableMatchingService(subwayDataService, converter);
    }

    // ────────────────────────────────────────────
    // 헬퍼 메서드
    // ────────────────────────────────────────────

    private SubwayArrivalEvent event(String lineId, String stationId, String direction, LocalDateTime arrivedAt) {
        return SubwayArrivalEvent.builder()
                .serviceDate(LocalDate.of(2026, 5, 3))
                .lineId(lineId).stationId(stationId).stationName("테스트역")
                .direction(direction).trainNo("T1")
                .arrivedAt(arrivedAt).firstObservedAt(arrivedAt).lastObservedAt(arrivedAt)
                .rawCount(1).eventSource("OBSERVED_CODE_1")
                .destinationKey("D1").build();
    }

    private SubwayTimetable timetable(String lineId, String tagoStationId, String direction, String arrTime) {
        return SubwayTimetable.builder()
                .lineId(lineId).tagoStationId(tagoStationId).stationName("테스트역")
                .direction(direction).dayType("03").arrTime(arrTime).depTime(arrTime)
                .endStationName("종착역").build();
    }

    private SubwayStation station(String stationId, String lineId, String tagoStationId) {
        return SubwayStation.builder()
                .stationId(stationId)
                .tagoStationId(tagoStationId)
                .stationName("테스트역")
                .lineId(lineId)
                .build();
    }

    @SuppressWarnings("unchecked")
    private void stubSaveMatchIssuesReturnsInput() {
        when(subwayDataService.saveAllMatchIssues(any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private List<SubwayArrivalEventMatchIssue> captureIssues() {
        ArgumentCaptor<List<SubwayArrivalEventMatchIssue>> captor = ArgumentCaptor.forClass(List.class);
        verify(subwayDataService).saveAllMatchIssues(captor.capture());
        return captor.getValue();
    }

    // ────────────────────────────────────────────
    // TC1: 1:1 정확 매칭 → issue 0건
    // ────────────────────────────────────────────

    @Test
    void TC1_일대일_정확매칭이면_issue_0건이다() {
        SubwayStation st = station("S1", "1002", "T1");
        SubwayTimetable tt = timetable("1002", "T1", "U", "100000");
        SubwayArrivalEvent ev = event("1002", "S1", "내선", LocalDateTime.of(2026, 5, 3, 10, 0, 0));

        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(List.of(ev));
        when(subwayDataService.findMappableStations()).thenReturn(List.of(st));
        when(subwayDataService.findTimetablesByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(List.of(tt));
        stubSaveMatchIssuesReturnsInput();

        int result = service.matchForDate(SERVICE_DATE);

        assertThat(captureIssues()).isEmpty();
        assertThat(result).isEqualTo(0);
    }

    // ────────────────────────────────────────────
    // TC2: timetable 5개 / event 3개 → NO_RAW_EVENT 2건
    // ────────────────────────────────────────────

    @Test
    void TC2_시간표5개_이벤트3개이면_NO_RAW_EVENT_2건이다() {
        SubwayStation st = station("S1", "1002", "T1");

        List<SubwayArrivalEvent> events = List.of(
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 10, 0, 0)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 11, 0, 0)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 12, 0, 0)));

        List<SubwayTimetable> timetables = List.of(
                timetable("1002", "T1", "U", "100000"),
                timetable("1002", "T1", "U", "110000"),
                timetable("1002", "T1", "U", "120000"),
                timetable("1002", "T1", "U", "130000"),
                timetable("1002", "T1", "U", "140000"));

        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayDataService.findMappableStations()).thenReturn(List.of(st));
        when(subwayDataService.findTimetablesByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(timetables);
        stubSaveMatchIssuesReturnsInput();

        service.matchForDate(SERVICE_DATE);

        List<SubwayArrivalEventMatchIssue> issues = captureIssues();
        assertThat(issues).hasSize(2);
        assertThat(issues).allMatch(i -> MatchIssueType.NO_RAW_EVENT.name().equals(i.getIssueType()));
    }

    // ────────────────────────────────────────────
    // TC3: timetable 3개 / event 5개 → EXTRA_RAW_EVENT 2건
    // ────────────────────────────────────────────

    @Test
    void TC3_시간표3개_이벤트5개이면_EXTRA_RAW_EVENT_2건이다() {
        SubwayStation st = station("S1", "1002", "T1");

        List<SubwayArrivalEvent> events = List.of(
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 10, 0, 0)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 11, 0, 0)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 12, 0, 0)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 13, 0, 0)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 14, 0, 0)));

        List<SubwayTimetable> timetables = List.of(
                timetable("1002", "T1", "U", "100000"),
                timetable("1002", "T1", "U", "110000"),
                timetable("1002", "T1", "U", "120000"));

        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayDataService.findMappableStations()).thenReturn(List.of(st));
        when(subwayDataService.findTimetablesByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(timetables);
        stubSaveMatchIssuesReturnsInput();

        service.matchForDate(SERVICE_DATE);

        List<SubwayArrivalEventMatchIssue> issues = captureIssues();
        assertThat(issues).hasSize(2);
        assertThat(issues).allMatch(i -> MatchIssueType.EXTRA_RAW_EVENT.name().equals(i.getIssueType()));
    }

    // ────────────────────────────────────────────
    // TC4: tagoStationId가 null인 역 → mappableStations에 포함되지 않음 → MAPPING_MISSING N건
    // ────────────────────────────────────────────

    @Test
    void TC4_tagoStationId가_null이면_MAPPING_MISSING_N건이다() {
        // tago mapping이 없는 역 → findMappableStations()에서 제외됨
        List<SubwayArrivalEvent> events = List.of(
                event("1002", "S1", "내선", LocalDateTime.of(2026, 5, 3, 10, 0, 0)),
                event("1002", "S1", "내선", LocalDateTime.of(2026, 5, 3, 11, 0, 0)),
                event("1002", "S1", "내선", LocalDateTime.of(2026, 5, 3, 12, 0, 0)));

        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayDataService.findMappableStations()).thenReturn(List.of()); // S1은 매핑 불가
        when(subwayDataService.findTimetablesByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(List.of());
        stubSaveMatchIssuesReturnsInput();

        service.matchForDate(SERVICE_DATE);

        List<SubwayArrivalEventMatchIssue> issues = captureIssues();
        assertThat(issues).hasSize(3);
        assertThat(issues).allMatch(i -> MatchIssueType.MAPPING_MISSING.name().equals(i.getIssueType()));
    }

    // ────────────────────────────────────────────
    // TC5: timetable 0건 / event N건 → EXTRA_RAW_EVENT N건
    // ────────────────────────────────────────────

    @Test
    void TC5_시간표없고_이벤트3건이면_EXTRA_RAW_EVENT_3건이다() {
        SubwayStation st = station("S1", "1002", "T1");

        List<SubwayArrivalEvent> events = List.of(
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 10, 0, 0)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 11, 0, 0)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 12, 0, 0)));

        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayDataService.findMappableStations()).thenReturn(List.of(st));
        when(subwayDataService.findTimetablesByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(List.of());
        stubSaveMatchIssuesReturnsInput();

        service.matchForDate(SERVICE_DATE);

        List<SubwayArrivalEventMatchIssue> issues = captureIssues();
        assertThat(issues).hasSize(3);
        assertThat(issues).allMatch(i -> MatchIssueType.EXTRA_RAW_EVENT.name().equals(i.getIssueType()));
    }

    // ────────────────────────────────────────────
    // TC6: timetable N건 / event 0건 → NO_RAW_EVENT N건
    // ────────────────────────────────────────────

    @Test
    void TC6_이벤트없고_시간표2건이면_NO_RAW_EVENT_2건이다() {
        SubwayStation st = station("S1", "1002", "T1");

        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(List.of());
        when(subwayDataService.findMappableStations()).thenReturn(List.of(st));
        when(subwayDataService.findTimetablesByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(List.of(
                timetable("1002", "T1", "U", "100000"),
                timetable("1002", "T1", "U", "110000")));
        stubSaveMatchIssuesReturnsInput();

        service.matchForDate(SERVICE_DATE);

        List<SubwayArrivalEventMatchIssue> issues = captureIssues();
        assertThat(issues).hasSize(2);
        assertThat(issues).allMatch(i -> MatchIssueType.NO_RAW_EVENT.name().equals(i.getIssueType()));
    }

    // ────────────────────────────────────────────
    // TC7: 두 방향 분리 검증 (내선/외선 → U/D 각각 독립 매칭) → issue 0건
    // ────────────────────────────────────────────

    @Test
    void TC7_내선외선이_독립적으로_매칭되면_issue_0건이다() {
        SubwayStation st = station("S1", "1002", "T1");

        List<SubwayArrivalEvent> events = List.of(
                event("1002", "S1", "내선", LocalDateTime.of(2026, 5, 3, 10, 0, 0)),
                event("1002", "S1", "외선", LocalDateTime.of(2026, 5, 3, 10, 5, 0)));

        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayDataService.findMappableStations()).thenReturn(List.of(st));
        when(subwayDataService.findTimetablesByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(List.of(
                timetable("1002", "T1", "U", "100000"),
                timetable("1002", "T1", "D", "100500")));
        stubSaveMatchIssuesReturnsInput();

        service.matchForDate(SERVICE_DATE);

        assertThat(captureIssues()).isEmpty();
    }

    // ────────────────────────────────────────────
    // TC8: arrTime="0" timetable이 orderKey 기준 정렬되어 매칭 → issue 0건
    // ────────────────────────────────────────────

    @Test
    void TC8_arrTime0인_시간표가_orderKey_기준_정렬되어_매칭된다() {
        SubwayStation st = station("S1", "1002", "T1");

        SubwayTimetable tt1 = SubwayTimetable.builder()
                .lineId("1002").tagoStationId("T1").stationName("테스트역")
                .direction("U").dayType("03").arrTime("0").depTime("103000")
                .endStationName("종착역").build();
        SubwayTimetable tt2 = SubwayTimetable.builder()
                .lineId("1002").tagoStationId("T1").stationName("테스트역")
                .direction("U").dayType("03").arrTime("110000").depTime("110000")
                .endStationName("종착역").build();

        // 이벤트·timetable 순서 모두 뒤집혀 있음 → orderKey 정렬 검증
        List<SubwayArrivalEvent> events = List.of(
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 11, 0, 0)),
                event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 10, 30, 0)));

        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayDataService.findMappableStations()).thenReturn(List.of(st));
        when(subwayDataService.findTimetablesByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(List.of(tt2, tt1));
        stubSaveMatchIssuesReturnsInput();

        service.matchForDate(SERVICE_DATE);

        assertThat(captureIssues()).isEmpty();
    }

    // ────────────────────────────────────────────
    // TC9: delete-and-recompute idempotent
    // ────────────────────────────────────────────

    @Test
    void TC9_같은날짜로_두번_호출하면_delete가_2번_호출된다() {
        SubwayStation st = station("S1", "1002", "T1");
        SubwayTimetable tt = timetable("1002", "T1", "U", "100000");
        SubwayArrivalEvent ev = event("1002", "S1", "상행", LocalDateTime.of(2026, 5, 3, 10, 0, 0));

        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(List.of(ev));
        when(subwayDataService.findMappableStations()).thenReturn(List.of(st));
        when(subwayDataService.findTimetablesByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(List.of(tt));
        when(subwayDataService.saveAllMatchIssues(any())).thenAnswer(inv -> inv.getArgument(0));

        int result1 = service.matchForDate(SERVICE_DATE);
        int result2 = service.matchForDate(SERVICE_DATE);

        verify(subwayDataService, times(2)).deleteMatchIssuesByServiceDate(SERVICE_DATE);
        assertThat(result1).isEqualTo(result2);
    }
}
