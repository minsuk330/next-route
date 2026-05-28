package watoo.grd.nextroute.application.subway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.domain.subway.entity.MatchIssueType;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEvent;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEventMatchIssue;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubwayInferredArrivalCompletionServiceTest {

    static class FakeHolidayCalendar implements HolidayCalendar {
        @Override public boolean isHoliday(LocalDate date) { return false; }
    }

    @Mock SubwayDataService subwayDataService;
    @Mock SubwaySegmentLookup segmentLookup;

    SubwayInferredArrivalCompletionService service;

    private static final LocalDate DATE = LocalDate.of(2026, 5, 17);

    @BeforeEach
    void setUp() {
        service = new SubwayInferredArrivalCompletionService(
                subwayDataService, segmentLookup,
                new TimetableConverter(new FakeHolidayCalendar()),
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "lineIdsCsv", "1002");
        ReflectionTestUtils.setField(service, "dedupWindowMinutes", 5L);
        ReflectionTestUtils.setField(service, "matchingVersion", "v1");
    }

    // ── helpers ──────────────────────────────────────────────

    private SubwayArrivalRaw code3(String stationId, String trainNo, String receivedAt, String prevStationId) {
        return SubwayArrivalRaw.builder()
                .lineId("1002").stationId(stationId).stationName("교대")
                .direction("내선").trainNo(trainNo)
                .prevStationId(prevStationId)
                .destinationId("D1").destinationName("성수행")
                .trainType("일반").arrivalCode("3")
                .currentMessage("사당 출발").receivedAt(receivedAt)
                .collectedAt(LocalDateTime.now())
                .build();
    }

    private SubwayArrivalEventMatchIssue noIssue(String stationId, int orderIndex) {
        return SubwayArrivalEventMatchIssue.builder()
                .serviceDate(DATE)
                .issueType(MatchIssueType.NO_RAW_EVENT.name())
                .lineId("1002").stationId(stationId).direction("U")
                .dayType("01").timetableOrderIndex(orderIndex)
                .build();
    }

    private SubwayStation station(String stationId, String name) {
        return SubwayStation.builder().stationId(stationId).stationName(name).lineId("1002").build();
    }

    private SubwayArrivalEvent observed(String stationId, String receivedAt) {
        return SubwayArrivalEvent.builder()
                .serviceDate(DATE).lineId("1002").stationId(stationId)
                .trainNo("OBS").direction("내선").eventSource("OBSERVED_CODE_1")
                .arrivedAt(LocalDateTime.parse(receivedAt.replace(' ', 'T')))
                .build();
    }

    @SuppressWarnings("unchecked")
    private void stubSaveReturnsInput() {
        when(subwayDataService.saveAllArrivalEvents(any())).thenAnswer(i -> i.getArgument(0));
    }

    private void stubStations() {
        when(subwayDataService.findAllStations())
                .thenReturn(List.of(station("S1", "교대"), station("P1", "사당")));
    }

    @SuppressWarnings("unchecked")
    private List<SubwayArrivalEvent> captureSaved() {
        ArgumentCaptor<List<SubwayArrivalEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(subwayDataService).saveAllArrivalEvents(captor.capture());
        return captor.getValue();
    }

    // ── tests ────────────────────────────────────────────────

    @Test
    void NO_없으면_저장도_호출하지_않고_0_반환() {
        when(subwayDataService.findNoRawEventIssues(eq(DATE), anyCollection())).thenReturn(List.of());

        int n = service.completeForDate(DATE);

        assertThat(n).isZero();
        verify(subwayDataService, never()).saveAllArrivalEvents(any());
    }

    @Test
    void segment_있으면_NO슬롯을_inferred_event로_보완하고_arrivedAt은_received_plus_travel() {
        when(subwayDataService.findNoRawEventIssues(eq(DATE), anyCollection()))
                .thenReturn(List.of(noIssue("S1", 0)));
        when(subwayDataService.findPrevDepartureCandidatesInRange(any(), any(), anyCollection()))
                .thenReturn(List.of(code3("S1", "T1", "2026-05-17 10:00:00", "P1")));
        stubStations();
        when(segmentLookup.get("1002", "사당", "교대")).thenReturn(90.0);
        stubSaveReturnsInput();

        int n = service.completeForDate(DATE);

        assertThat(n).isEqualTo(1);
        SubwayArrivalEvent ev = captureSaved().get(0);
        assertThat(ev.getEventSource()).isEqualTo("INFERRED_FROM_PREV_DEPARTURE");
        assertThat(ev.getStationId()).isEqualTo("S1");
        assertThat(ev.getArrivedAt()).isEqualTo(LocalDateTime.of(2026, 5, 17, 10, 1, 30));
        assertThat(ev.getFirstObservedAt()).isEqualTo(LocalDateTime.of(2026, 5, 17, 10, 0, 0));
    }

    @Test
    void segment_없으면_후보_제외되어_저장_없음_D3() {
        when(subwayDataService.findNoRawEventIssues(eq(DATE), anyCollection()))
                .thenReturn(List.of(noIssue("S1", 0)));
        when(subwayDataService.findPrevDepartureCandidatesInRange(any(), any(), anyCollection()))
                .thenReturn(List.of(code3("S1", "T1", "2026-05-17 10:00:00", "P1")));
        stubStations();
        when(segmentLookup.get(any(), any(), any())).thenReturn(null);
        stubSaveReturnsInput();

        int n = service.completeForDate(DATE);

        assertThat(n).isZero();
        assertThat(captureSaved()).isEmpty();
    }

    @Test
    void D1_시간창_내_OBSERVED_CODE_1이_있으면_후보_제외() {
        when(subwayDataService.findNoRawEventIssues(eq(DATE), anyCollection()))
                .thenReturn(List.of(noIssue("S1", 0)));
        when(subwayDataService.findPrevDepartureCandidatesInRange(any(), any(), anyCollection()))
                .thenReturn(List.of(code3("S1", "T1", "2026-05-17 10:00:00", "P1")));
        stubStations();
        when(segmentLookup.get("1002", "사당", "교대")).thenReturn(90.0);
        // 후보 arrivedAt = 10:01:30, 관측 10:03:00 → 1.5분 차 → window(5분) 내 → 제외
        when(subwayDataService.findArrivalEventsByServiceDate(DATE))
                .thenReturn(List.of(observed("S1", "2026-05-17 10:03:00")));
        stubSaveReturnsInput();

        int n = service.completeForDate(DATE);

        assertThat(n).isZero();
        assertThat(captureSaved()).isEmpty();
    }

    @Test
    void D2_NO슬롯_수만큼만_저장하고_초과_후보는_드롭() {
        when(subwayDataService.findNoRawEventIssues(eq(DATE), anyCollection()))
                .thenReturn(List.of(noIssue("S1", 0))); // NO slot 1개
        when(subwayDataService.findPrevDepartureCandidatesInRange(any(), any(), anyCollection()))
                .thenReturn(List.of(
                        code3("S1", "T1", "2026-05-17 10:00:00", "P1"),
                        code3("S1", "T2", "2026-05-17 10:20:00", "P1"))); // 후보 2개(다른 train)
        stubStations();
        when(segmentLookup.get("1002", "사당", "교대")).thenReturn(90.0);
        stubSaveReturnsInput();

        int n = service.completeForDate(DATE);

        assertThat(n).isEqualTo(1); // slot 1개 → 1개만 저장
        SubwayArrivalEvent ev = captureSaved().get(0);
        // order 정렬 후 앞선 후보(T1, 10:00 → 10:01:30) 채택
        assertThat(ev.getArrivedAt()).isEqualTo(LocalDateTime.of(2026, 5, 17, 10, 1, 30));
    }

    @Test
    void 십일분_gap_같은train은_subgroup_2개로_분리되어_NO슬롯_2개를_채운다() {
        when(subwayDataService.findNoRawEventIssues(eq(DATE), anyCollection()))
                .thenReturn(List.of(noIssue("S1", 0), noIssue("S1", 1))); // NO slot 2개
        when(subwayDataService.findPrevDepartureCandidatesInRange(any(), any(), anyCollection()))
                .thenReturn(List.of(
                        code3("S1", "T1", "2026-05-17 10:00:00", "P1"),
                        code3("S1", "T1", "2026-05-17 10:11:00", "P1"))); // 11분 gap
        stubStations();
        when(segmentLookup.get("1002", "사당", "교대")).thenReturn(60.0);
        stubSaveReturnsInput();

        int n = service.completeForDate(DATE);

        assertThat(n).isEqualTo(2);
        assertThat(captureSaved()).extracting(SubwayArrivalEvent::getArrivedAt)
                .containsExactlyInAnyOrder(
                        LocalDateTime.of(2026, 5, 17, 10, 1, 0),
                        LocalDateTime.of(2026, 5, 17, 10, 12, 0));
    }

    @Test
    void NO슬롯이_없는_key의_code3는_무시된다() {
        when(subwayDataService.findNoRawEventIssues(eq(DATE), anyCollection()))
                .thenReturn(List.of(noIssue("S1", 0)));
        when(subwayDataService.findPrevDepartureCandidatesInRange(any(), any(), anyCollection()))
                .thenReturn(List.of(code3("S9", "T1", "2026-05-17 10:00:00", "P1"))); // NO 없는 역 S9
        stubStations();
        stubSaveReturnsInput();

        int n = service.completeForDate(DATE);

        assertThat(n).isZero();
        assertThat(captureSaved()).isEmpty();
    }

    // ── V2: COUNT_MISMATCH 기반 보강 ─────────────────────────────────────────

    private SubwayArrivalEventMatchIssue countMismatchIssue(String stationId, int ttCount, int evCount) {
        return SubwayArrivalEventMatchIssue.builder()
                .serviceDate(DATE)
                .issueType(MatchIssueType.COUNT_MISMATCH.name())
                .lineId("1002").stationId(stationId).direction("U")
                .dayType("01")
                .timetableCount(ttCount).eventCount(evCount)
                .build();
    }

    @Test
    void v2_COUNT_MISMATCH_그룹의_부족분만큼_보강한다() {
        ReflectionTestUtils.setField(service, "matchingVersion", "v2");
        // 시간표 3 vs 이벤트 1 → 부족 2 slot
        when(subwayDataService.findCountMismatchIssues(eq(DATE), anyCollection()))
                .thenReturn(List.of(countMismatchIssue("S1", 3, 1)));
        when(subwayDataService.findPrevDepartureCandidatesInRange(any(), any(), anyCollection()))
                .thenReturn(List.of(
                        code3("S1", "T1", "2026-05-17 10:00:00", "P1"),
                        code3("S1", "T2", "2026-05-17 10:20:00", "P1"),
                        code3("S1", "T3", "2026-05-17 10:40:00", "P1"))); // 후보 3개
        stubStations();
        when(segmentLookup.get("1002", "사당", "교대")).thenReturn(90.0);
        stubSaveReturnsInput();

        int n = service.completeForDate(DATE);

        assertThat(n).isEqualTo(2); // 부족 2 → 2개만
    }

    @Test
    void v2_event가_timetable보다_많은_그룹은_보강_대상_아님() {
        ReflectionTestUtils.setField(service, "matchingVersion", "v2");
        // 시간표 1 vs 이벤트 3 → 부족 0
        when(subwayDataService.findCountMismatchIssues(eq(DATE), anyCollection()))
                .thenReturn(List.of(countMismatchIssue("S1", 1, 3)));

        int n = service.completeForDate(DATE);

        assertThat(n).isZero();
        verify(subwayDataService, never()).saveAllArrivalEvents(any());
    }
}
