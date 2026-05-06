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
import watoo.grd.nextroute.domain.subway.repository.SubwayTimetableRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayTimetableRepository.TimetableCoverageProjection;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TimetableMatchingServiceTest {

    // ────────────────────────────────────────────
    // FakeHolidayCalendar: 2026-05-05 어린이날 공휴일 등록
    // ────────────────────────────────────────────
    static class FakeHolidayCalendar implements HolidayCalendar {
        private final Set<LocalDate> holidays;

        FakeHolidayCalendar(LocalDate... dates) {
            this.holidays = Set.of(dates);
        }

        @Override
        public boolean isHoliday(LocalDate date) {
            return holidays.contains(date);
        }
    }

    @Mock
    SubwayDataService subwayDataService;

    @Mock
    SubwayTimetableRepository subwayTimetableRepository;

    TimetableMatchingService service;

    /** 테스트에서 공통으로 사용할 serviceDate (2026-05-03 일요일 → dayType="03") */
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 5, 3);

    @BeforeEach
    void setUp() {
        TimetableConverter converter = new TimetableConverter(
                new FakeHolidayCalendar(LocalDate.of(2026, 5, 5)));
        service = new TimetableMatchingService(subwayDataService, converter, subwayTimetableRepository);
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

    /** saveAllMatchIssues가 입력 리스트를 그대로 반환하도록 스텁 */
    @SuppressWarnings("unchecked")
    private void stubSaveMatchIssuesReturnsInput() {
        when(subwayDataService.saveAllMatchIssues(any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /** TimetableCoverageProjection 익명 구현 생성 헬퍼 */
    private TimetableCoverageProjection coverage(String lineId, String tagoStationId, String direction) {
        return new TimetableCoverageProjection() {
            @Override public String getLineId()         { return lineId; }
            @Override public String getTagoStationId()  { return tagoStationId; }
            @Override public String getDirection()      { return direction; }
        };
    }

    /** saveAllMatchIssues 호출을 캡처하여 이슈 리스트를 반환 */
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
        SubwayArrivalEvent ev = event("1002", "S1", "내선",
                LocalDateTime.of(2026, 5, 3, 10, 0, 0));
        SubwayStation st = station("S1", "1002", "T1");
        SubwayTimetable tt = timetable("1002", "T1", "U", "100000"); // 10:00:00

        // 이벤트: S1, 내선 → dayType "03"
        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE))
                .thenReturn(List.of(ev));

        // coverage: lineId=1002, tagoStationId=T1, direction=U
        when(subwayDataService.findTimetableCoverage("03"))
                .thenReturn(List.of(coverage("1002", "T1", "U")));

        when(subwayDataService.findByLineIdAndTagoStationId("1002", "T1"))
                .thenReturn(List.of(st));

        when(subwayDataService.findByStationIdAndLineId("S1", "1002"))
                .thenReturn(Optional.of(st));

        when(subwayTimetableRepository.findByTagoStationIdAndDayTypeAndDirection("T1", "03", "U"))
                .thenReturn(List.of(tt));

        stubSaveMatchIssuesReturnsInput();

        int result = service.matchForDate(SERVICE_DATE);

        List<SubwayArrivalEventMatchIssue> issues = captureIssues();
        assertThat(issues).isEmpty();
        assertThat(result).isEqualTo(0);
    }

    // ────────────────────────────────────────────
    // TC2: timetable 5개 / event 3개 → NO_RAW_EVENT 2건
    // ────────────────────────────────────────────

    @Test
    void TC2_시간표5개_이벤트3개이면_NO_RAW_EVENT_2건이다() {
        String lineId = "1002"; String stationId = "S1"; String tagoId = "T1";
        SubwayStation st = station(stationId, lineId, tagoId);

        List<SubwayArrivalEvent> events = List.of(
                event(lineId, stationId, "상행", LocalDateTime.of(2026, 5, 3, 10, 0, 0)),
                event(lineId, stationId, "상행", LocalDateTime.of(2026, 5, 3, 11, 0, 0)),
                event(lineId, stationId, "상행", LocalDateTime.of(2026, 5, 3, 12, 0, 0)));

        List<SubwayTimetable> timetables = List.of(
                timetable(lineId, tagoId, "U", "100000"),
                timetable(lineId, tagoId, "U", "110000"),
                timetable(lineId, tagoId, "U", "120000"),
                timetable(lineId, tagoId, "U", "130000"),
                timetable(lineId, tagoId, "U", "140000"));

        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayDataService.findTimetableCoverage("03"))
                .thenReturn(List.of(coverage(lineId, tagoId, "U")));
        when(subwayDataService.findByLineIdAndTagoStationId(lineId, tagoId)).thenReturn(List.of(st));
        when(subwayDataService.findByStationIdAndLineId(stationId, lineId)).thenReturn(Optional.of(st));
        when(subwayTimetableRepository.findByTagoStationIdAndDayTypeAndDirection(tagoId, "03", "U"))
                .thenReturn(timetables);
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
        String lineId = "1002"; String stationId = "S1"; String tagoId = "T1";
        SubwayStation st = station(stationId, lineId, tagoId);

        List<SubwayArrivalEvent> events = List.of(
                event(lineId, stationId, "상행", LocalDateTime.of(2026, 5, 3, 10, 0, 0)),
                event(lineId, stationId, "상행", LocalDateTime.of(2026, 5, 3, 11, 0, 0)),
                event(lineId, stationId, "상행", LocalDateTime.of(2026, 5, 3, 12, 0, 0)),
                event(lineId, stationId, "상행", LocalDateTime.of(2026, 5, 3, 13, 0, 0)),
                event(lineId, stationId, "상행", LocalDateTime.of(2026, 5, 3, 14, 0, 0)));

        List<SubwayTimetable> timetables = List.of(
                timetable(lineId, tagoId, "U", "100000"),
                timetable(lineId, tagoId, "U", "110000"),
                timetable(lineId, tagoId, "U", "120000"));

        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayDataService.findTimetableCoverage("03"))
                .thenReturn(List.of(coverage(lineId, tagoId, "U")));
        when(subwayDataService.findByLineIdAndTagoStationId(lineId, tagoId)).thenReturn(List.of(st));
        when(subwayDataService.findByStationIdAndLineId(stationId, lineId)).thenReturn(Optional.of(st));
        when(subwayTimetableRepository.findByTagoStationIdAndDayTypeAndDirection(tagoId, "03", "U"))
                .thenReturn(timetables);
        stubSaveMatchIssuesReturnsInput();

        service.matchForDate(SERVICE_DATE);

        List<SubwayArrivalEventMatchIssue> issues = captureIssues();
        assertThat(issues).hasSize(2);
        assertThat(issues).allMatch(i -> MatchIssueType.EXTRA_RAW_EVENT.name().equals(i.getIssueType()));
    }

    // ────────────────────────────────────────────
    // TC4: station.tagoStationId == null → MAPPING_MISSING N건
    // ────────────────────────────────────────────

    @Test
    void TC4_tagoStationId가_null이면_MAPPING_MISSING_N건이다() {
        String lineId = "1002"; String stationId = "S1";
        // tagoStationId = null 인 역
        SubwayStation st = station(stationId, lineId, null);

        List<SubwayArrivalEvent> events = List.of(
                event(lineId, stationId, "내선", LocalDateTime.of(2026, 5, 3, 10, 0, 0)),
                event(lineId, stationId, "내선", LocalDateTime.of(2026, 5, 3, 11, 0, 0)),
                event(lineId, stationId, "내선", LocalDateTime.of(2026, 5, 3, 12, 0, 0)));

        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(events);
        // coverage 없음 (timetable-only key 없음)
        when(subwayDataService.findTimetableCoverage("03")).thenReturn(List.of());
        when(subwayDataService.findByStationIdAndLineId(stationId, lineId)).thenReturn(Optional.of(st));
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
        String lineId = "1002"; String stationId = "S1"; String tagoId = "T1";
        SubwayStation st = station(stationId, lineId, tagoId);

        List<SubwayArrivalEvent> events = List.of(
                event(lineId, stationId, "상행", LocalDateTime.of(2026, 5, 3, 10, 0, 0)),
                event(lineId, stationId, "상행", LocalDateTime.of(2026, 5, 3, 11, 0, 0)),
                event(lineId, stationId, "상행", LocalDateTime.of(2026, 5, 3, 12, 0, 0)));

        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(events);
        // coverage 비어있음 → timetable-only key 없음
        when(subwayDataService.findTimetableCoverage("03")).thenReturn(List.of());
        when(subwayDataService.findByStationIdAndLineId(stationId, lineId)).thenReturn(Optional.of(st));
        // timetable 조회 결과 비어있음
        when(subwayTimetableRepository.findByTagoStationIdAndDayTypeAndDirection(tagoId, "03", "U"))
                .thenReturn(List.of());
        stubSaveMatchIssuesReturnsInput();

        service.matchForDate(SERVICE_DATE);

        List<SubwayArrivalEventMatchIssue> issues = captureIssues();
        assertThat(issues).hasSize(3);
        assertThat(issues).allMatch(i -> MatchIssueType.EXTRA_RAW_EVENT.name().equals(i.getIssueType()));
    }

    // ────────────────────────────────────────────
    // TC6: timetable N건 / event 0건 (timetable-only key) → NO_RAW_EVENT N건
    // ────────────────────────────────────────────

    @Test
    void TC6_이벤트없고_시간표2건이면_NO_RAW_EVENT_2건이다() {
        String lineId = "1002"; String stationId = "S1"; String tagoId = "T1";
        SubwayStation st = station(stationId, lineId, tagoId);

        // 이벤트 없음
        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(List.of());
        // coverage: lineId=1002, tagoStationId=T1, direction=U
        when(subwayDataService.findTimetableCoverage("03"))
                .thenReturn(List.of(coverage(lineId, tagoId, "U")));
        when(subwayDataService.findByLineIdAndTagoStationId(lineId, tagoId)).thenReturn(List.of(st));
        when(subwayDataService.findByStationIdAndLineId(stationId, lineId)).thenReturn(Optional.of(st));
        when(subwayTimetableRepository.findByTagoStationIdAndDayTypeAndDirection(tagoId, "03", "U"))
                .thenReturn(List.of(
                        timetable(lineId, tagoId, "U", "100000"),
                        timetable(lineId, tagoId, "U", "110000")));
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
        String lineId = "1002"; String stationId = "S1"; String tagoId = "T1";
        SubwayStation st = station(stationId, lineId, tagoId);

        List<SubwayArrivalEvent> events = List.of(
                event(lineId, stationId, "내선", LocalDateTime.of(2026, 5, 3, 10, 0, 0)),
                event(lineId, stationId, "외선", LocalDateTime.of(2026, 5, 3, 10, 5, 0)));

        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayDataService.findTimetableCoverage("03")).thenReturn(List.of(
                coverage(lineId, tagoId, "U"),
                coverage(lineId, tagoId, "D")));
        when(subwayDataService.findByLineIdAndTagoStationId(lineId, tagoId)).thenReturn(List.of(st));
        when(subwayDataService.findByStationIdAndLineId(stationId, lineId)).thenReturn(Optional.of(st));
        when(subwayTimetableRepository.findByTagoStationIdAndDayTypeAndDirection(tagoId, "03", "U"))
                .thenReturn(List.of(timetable(lineId, tagoId, "U", "100000")));
        when(subwayTimetableRepository.findByTagoStationIdAndDayTypeAndDirection(tagoId, "03", "D"))
                .thenReturn(List.of(timetable(lineId, tagoId, "D", "100500")));
        stubSaveMatchIssuesReturnsInput();

        service.matchForDate(SERVICE_DATE);

        List<SubwayArrivalEventMatchIssue> issues = captureIssues();
        assertThat(issues).isEmpty();
    }

    // ────────────────────────────────────────────
    // TC8: arrTime="0" timetable이 orderKey 기준 정렬되어 매칭 → issue 0건
    // ────────────────────────────────────────────

    @Test
    void TC8_arrTime0인_시간표가_orderKey_기준_정렬되어_매칭된다() {
        String lineId = "1002"; String stationId = "S1"; String tagoId = "T1";
        SubwayStation st = station(stationId, lineId, tagoId);

        // timetable1: arrTime="0", depTime="103000" → scheduledArrivalAt ≈ 10:29:30 (orderKey 작음)
        // timetable2: arrTime="110000" → scheduledArrivalAt = 11:00:00 (orderKey 큼)
        SubwayTimetable tt1 = SubwayTimetable.builder()
                .lineId(lineId).tagoStationId(tagoId).stationName("테스트역")
                .direction("U").dayType("03").arrTime("0").depTime("103000")
                .endStationName("종착역").build();
        SubwayTimetable tt2 = SubwayTimetable.builder()
                .lineId(lineId).tagoStationId(tagoId).stationName("테스트역")
                .direction("U").dayType("03").arrTime("110000").depTime("110000")
                .endStationName("종착역").build();

        // event 순서 뒤집혀 있음: 11:00 먼저, 10:30 나중
        List<SubwayArrivalEvent> events = List.of(
                event(lineId, stationId, "상행", LocalDateTime.of(2026, 5, 3, 11, 0, 0)),
                event(lineId, stationId, "상행", LocalDateTime.of(2026, 5, 3, 10, 30, 0)));

        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayDataService.findTimetableCoverage("03"))
                .thenReturn(List.of(coverage(lineId, tagoId, "U")));
        when(subwayDataService.findByLineIdAndTagoStationId(lineId, tagoId)).thenReturn(List.of(st));
        when(subwayDataService.findByStationIdAndLineId(stationId, lineId)).thenReturn(Optional.of(st));
        // timetable 리스트도 순서 뒤집혀서 반환 (정렬 검증)
        when(subwayTimetableRepository.findByTagoStationIdAndDayTypeAndDirection(tagoId, "03", "U"))
                .thenReturn(List.of(tt2, tt1));
        stubSaveMatchIssuesReturnsInput();

        service.matchForDate(SERVICE_DATE);

        List<SubwayArrivalEventMatchIssue> issues = captureIssues();
        assertThat(issues).isEmpty();
    }

    // ────────────────────────────────────────────
    // TC9: delete-and-recompute idempotent
    //   matchForDate를 같은 날짜로 2번 실행 → deleteMatchIssuesByServiceDate 2번 호출 확인
    // ────────────────────────────────────────────

    @Test
    void TC9_같은날짜로_두번_호출하면_delete가_2번_호출된다() {
        String lineId = "1002"; String stationId = "S1"; String tagoId = "T1";
        SubwayStation st = station(stationId, lineId, tagoId);
        SubwayTimetable tt = timetable(lineId, tagoId, "U", "100000");
        SubwayArrivalEvent ev = event(lineId, stationId, "상행", LocalDateTime.of(2026, 5, 3, 10, 0, 0));

        // lenient 스텁 (2번 호출에서도 항상 동일 응답)
        when(subwayDataService.findArrivalEventsByServiceDate(SERVICE_DATE)).thenReturn(List.of(ev));
        when(subwayDataService.findTimetableCoverage("03"))
                .thenReturn(List.of(coverage(lineId, tagoId, "U")));
        when(subwayDataService.findByLineIdAndTagoStationId(lineId, tagoId)).thenReturn(List.of(st));
        when(subwayDataService.findByStationIdAndLineId(stationId, lineId)).thenReturn(Optional.of(st));
        when(subwayTimetableRepository.findByTagoStationIdAndDayTypeAndDirection(tagoId, "03", "U"))
                .thenReturn(List.of(tt));
        when(subwayDataService.saveAllMatchIssues(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        // 같은 날짜로 2번 실행
        int result1 = service.matchForDate(SERVICE_DATE);
        int result2 = service.matchForDate(SERVICE_DATE);

        // deleteMatchIssuesByServiceDate가 2번 호출됐는지 검증
        verify(subwayDataService, times(2)).deleteMatchIssuesByServiceDate(SERVICE_DATE);
        // 두 번 모두 같은 issue 수 반환
        assertThat(result1).isEqualTo(result2);
    }
}
