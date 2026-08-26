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
import watoo.grd.nextroute.domain.subway.repository.SubwayArrivalEventRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayTimetableRepository;
import watoo.grd.nextroute.domain.subway.service.SubwayMatchIssueStore;

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

    @Mock SubwayArrivalEventRepository subwayArrivalEventRepository;
    @Mock SubwayStationRepository subwayStationRepository;
    @Mock SubwayTimetableRepository subwayTimetableRepository;
    @Mock SubwayMatchIssueStore subwayMatchIssueStore;

    TimetableMatchingService service;

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 5, 3);

    @BeforeEach
    void setUp() {
        TimetableConverter converter = new TimetableConverter(
                new FakeHolidayCalendar(LocalDate.of(2026, 5, 5)));
        DestinationNormalizer destinationNormalizer = new DestinationNormalizer();
        service = new TimetableMatchingService(subwayArrivalEventRepository, subwayStationRepository,
                subwayTimetableRepository, subwayMatchIssueStore, converter,
                new EventTimetablePairer(converter),
                new EventTimetablePairerV2(converter, destinationNormalizer),
                destinationNormalizer,
                new com.fasterxml.jackson.databind.ObjectMapper());
        service.matchingVersion = "v1";
        service.maxMatchDistanceSeconds = 1800L;
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
        when(subwayMatchIssueStore.saveAllMatchIssues(any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private List<SubwayArrivalEventMatchIssue> captureIssues() {
        ArgumentCaptor<List<SubwayArrivalEventMatchIssue>> captor = ArgumentCaptor.forClass(List.class);
        verify(subwayMatchIssueStore).saveAllMatchIssues(captor.capture());
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

        when(subwayArrivalEventRepository.findByServiceDate(SERVICE_DATE)).thenReturn(List.of(ev));
        when(subwayStationRepository.findByTagoStationIdIsNotNull()).thenReturn(List.of(st));
        when(subwayTimetableRepository.findByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(List.of(tt));
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

        when(subwayArrivalEventRepository.findByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayStationRepository.findByTagoStationIdIsNotNull()).thenReturn(List.of(st));
        when(subwayTimetableRepository.findByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(timetables);
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

        when(subwayArrivalEventRepository.findByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayStationRepository.findByTagoStationIdIsNotNull()).thenReturn(List.of(st));
        when(subwayTimetableRepository.findByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(timetables);
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

        when(subwayArrivalEventRepository.findByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayStationRepository.findByTagoStationIdIsNotNull()).thenReturn(List.of()); // S1은 매핑 불가
        when(subwayTimetableRepository.findByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(List.of());
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

        when(subwayArrivalEventRepository.findByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayStationRepository.findByTagoStationIdIsNotNull()).thenReturn(List.of(st));
        when(subwayTimetableRepository.findByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(List.of());
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

        when(subwayArrivalEventRepository.findByServiceDate(SERVICE_DATE)).thenReturn(List.of());
        when(subwayStationRepository.findByTagoStationIdIsNotNull()).thenReturn(List.of(st));
        when(subwayTimetableRepository.findByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(List.of(
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

        when(subwayArrivalEventRepository.findByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayStationRepository.findByTagoStationIdIsNotNull()).thenReturn(List.of(st));
        when(subwayTimetableRepository.findByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(List.of(
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

        when(subwayArrivalEventRepository.findByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayStationRepository.findByTagoStationIdIsNotNull()).thenReturn(List.of(st));
        when(subwayTimetableRepository.findByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(List.of(tt2, tt1));
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

        when(subwayArrivalEventRepository.findByServiceDate(SERVICE_DATE)).thenReturn(List.of(ev));
        when(subwayStationRepository.findByTagoStationIdIsNotNull()).thenReturn(List.of(st));
        when(subwayTimetableRepository.findByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(List.of(tt));
        when(subwayMatchIssueStore.saveAllMatchIssues(any())).thenAnswer(inv -> inv.getArgument(0));

        int result1 = service.matchForDate(SERVICE_DATE);
        int result2 = service.matchForDate(SERVICE_DATE);

        verify(subwayMatchIssueStore, times(2)).deleteMatchIssuesByServiceDate(SERVICE_DATE);
        assertThat(result1).isEqualTo(result2);
    }

    // ── V2: group당 issue 1건 + chunking ─────────────────────────────────────

    private SubwayTimetable ttWithEnd(String tagoId, String dir, String arr, String end) {
        return SubwayTimetable.builder()
                .lineId("1002").tagoStationId(tagoId).stationName("테스트역")
                .direction(dir).dayType("03").arrTime(arr).depTime(arr)
                .endStationName(end).build();
    }

    private SubwayArrivalEvent evWithDest(String stationId, String dir, String trainNo,
                                          LocalDateTime arrivedAt, String destName) {
        return SubwayArrivalEvent.builder()
                .serviceDate(SERVICE_DATE).lineId("1002").stationId(stationId)
                .stationName("테스트역").direction(dir).trainNo(trainNo)
                .destinationName(destName)
                .arrivedAt(arrivedAt).firstObservedAt(arrivedAt).lastObservedAt(arrivedAt)
                .rawCount(1).eventSource("OBSERVED_CODE_1").destinationKey("DK").build();
    }

    @Test
    void TC_V2_시간창_초과_group은_pair_수와_무관하게_issue_1건만_저장한다() {
        service.matchingVersion = "v2";
        SubwayStation st = station("S1", "1002", "T1");
        // 3 pair 모두 19h+ 차이 (count equal + 모두 over-window)
        List<SubwayTimetable> tts = List.of(
                ttWithEnd("T1", "U", "054800", "한강진"),
                ttWithEnd("T1", "U", "054900", "한강진"),
                ttWithEnd("T1", "U", "055000", "한강진"));
        List<SubwayArrivalEvent> events = List.of(
                evWithDest("S1", "내선", "T1", LocalDateTime.of(2026, 5, 4, 0, 54, 0), "한강진"),
                evWithDest("S1", "내선", "T2", LocalDateTime.of(2026, 5, 4, 0, 55, 0), "한강진"),
                evWithDest("S1", "내선", "T3", LocalDateTime.of(2026, 5, 4, 0, 56, 0), "한강진"));

        when(subwayArrivalEventRepository.findByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayStationRepository.findByTagoStationIdIsNotNull()).thenReturn(List.of(st));
        when(subwayTimetableRepository.findByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(tts);
        stubSaveMatchIssuesReturnsInput();

        service.matchForDate(SERVICE_DATE);

        // saveAllMatchIssues 호출 1회 (chunk size 미달) + 그 안에 issue 1건
        verify(subwayMatchIssueStore, times(1)).saveAllMatchIssues(any());
        List<SubwayArrivalEventMatchIssue> issues = captureIssues();
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).getIssueType())
                .isEqualTo(MatchIssueType.MATCH_REJECTED_TIME_DISTANCE.name());
        // details JSON에 allAbsDelaysSeconds 3개 포함
        assertThat(issues.get(0).getDetails()).contains("allAbsDelaysSeconds");
        assertThat(issues.get(0).getDetails()).contains("\"pairCount\":3");
    }

    @Test
    void TC_V2_destination_mismatch_group은_pair_수와_무관하게_issue_1건만_저장한다() {
        service.matchingVersion = "v2";
        SubwayStation st = station("S1", "1002", "T1");
        // 2 pair 모두 destination 다름
        List<SubwayTimetable> tts = List.of(
                ttWithEnd("T1", "U", "100000", "응암"),
                ttWithEnd("T1", "U", "110000", "응암"));
        List<SubwayArrivalEvent> events = List.of(
                evWithDest("S1", "내선", "T1", LocalDateTime.of(2026, 5, 3, 10, 0, 30), "한강진"),
                evWithDest("S1", "내선", "T2", LocalDateTime.of(2026, 5, 3, 11, 0, 30), "한강진"));

        when(subwayArrivalEventRepository.findByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayStationRepository.findByTagoStationIdIsNotNull()).thenReturn(List.of(st));
        when(subwayTimetableRepository.findByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(tts);
        stubSaveMatchIssuesReturnsInput();

        service.matchForDate(SERVICE_DATE);

        verify(subwayMatchIssueStore, times(1)).saveAllMatchIssues(any());
        List<SubwayArrivalEventMatchIssue> issues = captureIssues();
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).getIssueType())
                .isEqualTo(MatchIssueType.DESTINATION_MISMATCH.name());
        assertThat(issues.get(0).getDetails()).contains("\"mismatchCount\":2");
    }

    @Test
    void TC_V2_chunk_size_초과시_여러번_save_호출된다() {
        service.matchingVersion = "v2";
        service.issueChunkSize = 5; // 5 chunk size로 강제

        // 12개 station × 2 pair 모두 over-window → 12 group → issue 12건
        // 첫 save 5건, 두번째 5건, 잔여 2건 → 총 3회 save
        List<SubwayStation> stations = new java.util.ArrayList<>();
        List<SubwayTimetable> tts = new java.util.ArrayList<>();
        List<SubwayArrivalEvent> events = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String sid = "S" + i;
            String tid = "T" + i;
            stations.add(station(sid, "1002", tid));
            tts.add(ttWithEnd(tid, "U", "054800", "한강진"));
            tts.add(ttWithEnd(tid, "U", "054900", "한강진"));
            events.add(evWithDest(sid, "내선", "T1_" + i,
                    LocalDateTime.of(2026, 5, 4, 0, 54, 0), "한강진"));
            events.add(evWithDest(sid, "내선", "T2_" + i,
                    LocalDateTime.of(2026, 5, 4, 0, 55, 0), "한강진"));
        }

        when(subwayArrivalEventRepository.findByServiceDate(SERVICE_DATE)).thenReturn(events);
        when(subwayStationRepository.findByTagoStationIdIsNotNull()).thenReturn(stations);
        when(subwayTimetableRepository.findByDayTypeAndLineIdIn(eq("03"), any())).thenReturn(tts);
        stubSaveMatchIssuesReturnsInput();

        int total = service.matchForDate(SERVICE_DATE);

        // chunk size 5 → 12 issue ÷ 5 = 3회 (5 + 5 + 2)
        verify(subwayMatchIssueStore, times(3)).saveAllMatchIssues(any());
        verify(subwayMatchIssueStore, times(3)).flushAndClear();
        assertThat(total).isEqualTo(12);
    }
}
