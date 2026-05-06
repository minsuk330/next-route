package watoo.grd.nextroute.application.subway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEvent;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubwayArrivalEventDerivationServiceTest {

    @Mock
    SubwayDataService subwayDataService;

    SubwayArrivalEventDerivationService service;

    @BeforeEach
    void setUp() {
        service = new SubwayArrivalEventDerivationService(subwayDataService, new ObjectMapper());
    }

    // ──────────────────────────────────────────────
    // 헬퍼 — 기본 필드로 SubwayArrivalRaw 생성
    // ──────────────────────────────────────────────

    private SubwayArrivalRaw raw(String lineId, String stationId, String direction,
                                  String trainNo, String receivedAt) {
        return SubwayArrivalRaw.builder()
                .lineId(lineId).stationId(stationId).stationName("강남")
                .direction(direction).trainNo(trainNo)
                .destinationId("D1").destinationName("성수행")
                .trainType("일반").arrivalCode("1")
                .currentMessage("강남 도착").receivedAt(receivedAt)
                .collectedAt(LocalDateTime.now())
                .build();
    }

    /** destinationId / destinationName 을 직접 지정하는 헬퍼 */
    private SubwayArrivalRaw rawWithDest(String lineId, String stationId, String direction,
                                          String trainNo, String receivedAt,
                                          String destinationId, String destinationName) {
        return SubwayArrivalRaw.builder()
                .lineId(lineId).stationId(stationId).stationName("강남")
                .direction(direction).trainNo(trainNo)
                .destinationId(destinationId).destinationName(destinationName)
                .trainType("일반").arrivalCode("1")
                .currentMessage("강남 도착").receivedAt(receivedAt)
                .collectedAt(LocalDateTime.now())
                .build();
    }

    /** saveAllArrivalEvents 가 입력 리스트를 그대로 반환하도록 스텁 */
    @SuppressWarnings("unchecked")
    private void stubSaveReturnsInput() {
        when(subwayDataService.saveAllArrivalEvents(any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ──────────────────────────────────────────────
    // TC1: 단일 도착 이벤트 생성
    // ──────────────────────────────────────────────

    @Test
    void 같은_그룹키_raw_두_개는_이벤트_하나로_병합된다() {
        SubwayArrivalRaw raw1 = raw("1002", "S1", "내선", "T1", "2026-05-03 10:00:00");
        SubwayArrivalRaw raw2 = raw("1002", "S1", "내선", "T1", "2026-05-03 10:00:15");

        when(subwayDataService.findArrivalCandidatesInRange(any(), any())).thenReturn(List.of(raw1, raw2));
        stubSaveReturnsInput();

        /// 이제 테스트 대상 메서드를 실행한다. 이 전까진 테스트 사전 작업
        service.deriveForDate(LocalDate.of(2026, 5, 3));

      @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubwayArrivalEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(subwayDataService).saveAllArrivalEvents(captor.capture());

        List<SubwayArrivalEvent> saved = captor.getValue();
        assertThat(saved).hasSize(1);

        SubwayArrivalEvent event = saved.get(0);
        assertThat(event.getArrivedAt()).isEqualTo(LocalDateTime.of(2026, 5, 3, 10, 0, 0));
        assertThat(event.getLastObservedAt()).isEqualTo(LocalDateTime.of(2026, 5, 3, 10, 0, 15));
        assertThat(event.getRawCount()).isEqualTo(2);
        assertThat(event.getEventSource()).isEqualTo("OBSERVED_CODE_1");
    }

    // ──────────────────────────────────────────────
    // TC2: 10분 초과 gap → 두 이벤트로 분리
    // ──────────────────────────────────────────────

    @Test
    void 십일분_gap이면_이벤트_두_개로_분리된다() {
        SubwayArrivalRaw raw1 = raw("1002", "S1", "내선", "T1", "2026-05-03 10:00:00");
        SubwayArrivalRaw raw2 = raw("1002", "S1", "내선", "T1", "2026-05-03 10:11:00");

        when(subwayDataService.findArrivalCandidatesInRange(any(), any()))
                .thenReturn(List.of(raw1, raw2));
        stubSaveReturnsInput();

        service.deriveForDate(LocalDate.of(2026, 5, 3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubwayArrivalEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(subwayDataService).saveAllArrivalEvents(captor.capture());

        assertThat(captor.getValue()).hasSize(2);
    }

    // ──────────────────────────────────────────────
    // TC3: 정확히 10분 gap → 같은 이벤트 (경계값)
    // ──────────────────────────────────────────────

    @Test
    void 정확히_십분_gap이면_이벤트_하나로_병합된다() {
        SubwayArrivalRaw raw1 = raw("1002", "S1", "내선", "T1", "2026-05-03 10:00:00");
        SubwayArrivalRaw raw2 = raw("1002", "S1", "내선", "T1", "2026-05-03 10:10:00");

        when(subwayDataService.findArrivalCandidatesInRange(any(), any()))
                .thenReturn(List.of(raw1, raw2));
        stubSaveReturnsInput();

        service.deriveForDate(LocalDate.of(2026, 5, 3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubwayArrivalEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(subwayDataService).saveAllArrivalEvents(captor.capture());

        assertThat(captor.getValue()).hasSize(1);
    }

    // ──────────────────────────────────────────────
    // TC4: 서로 다른 direction → 두 이벤트
    // ──────────────────────────────────────────────

    @Test
    void 다른_direction은_별도_이벤트로_분리된다() {
        SubwayArrivalRaw raw1 = raw("1002", "S1", "내선", "T1", "2026-05-03 10:00:00");
        SubwayArrivalRaw raw2 = raw("1002", "S1", "외선", "T1", "2026-05-03 10:00:00");

        when(subwayDataService.findArrivalCandidatesInRange(any(), any()))
                .thenReturn(List.of(raw1, raw2));
        stubSaveReturnsInput();

        service.deriveForDate(LocalDate.of(2026, 5, 3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubwayArrivalEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(subwayDataService).saveAllArrivalEvents(captor.capture());

        assertThat(captor.getValue()).hasSize(2);
    }

    // ──────────────────────────────────────────────
    // TC5: destination 우선순위 — destinationId 있는 row 우선
    // ──────────────────────────────────────────────

    @Test
    void destinationId_있는_row가_없는_row보다_우선된다() {
        SubwayArrivalRaw raw1 = rawWithDest("1002", "S1", "내선", "T1",
                "2026-05-03 10:00:00", null, "성수행");
        SubwayArrivalRaw raw2 = rawWithDest("1002", "S1", "내선", "T1",
                "2026-05-03 10:00:15", "D99", "성수행");

        when(subwayDataService.findArrivalCandidatesInRange(any(), any()))
                .thenReturn(List.of(raw1, raw2));
        stubSaveReturnsInput();

        service.deriveForDate(LocalDate.of(2026, 5, 3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubwayArrivalEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(subwayDataService).saveAllArrivalEvents(captor.capture());

        SubwayArrivalEvent event = captor.getValue().get(0);
        assertThat(event.getDestinationKey()).isEqualTo("D99");
        assertThat(event.getDestinationId()).isEqualTo("D99");
    }

    // ──────────────────────────────────────────────
    // TC6: destination 충돌 감지
    // ──────────────────────────────────────────────

    @Test
    void 같은_그룹에_destinationId가_다른_두_raw는_충돌로_감지된다() {
        SubwayArrivalRaw raw1 = rawWithDest("1002", "S1", "내선", "T1",
                "2026-05-03 10:00:00", "D1", "성수행");
        SubwayArrivalRaw raw2 = rawWithDest("1002", "S1", "내선", "T1",
                "2026-05-03 10:00:15", "D2", "구로디지털단지행");

        when(subwayDataService.findArrivalCandidatesInRange(any(), any()))
                .thenReturn(List.of(raw1, raw2));
        stubSaveReturnsInput();

        service.deriveForDate(LocalDate.of(2026, 5, 3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubwayArrivalEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(subwayDataService).saveAllArrivalEvents(captor.capture());

        SubwayArrivalEvent event = captor.getValue().get(0);
        assertThat(event.getDestinationConflicted()).isTrue();
        assertThat(event.getDestinationConflictCount()).isGreaterThanOrEqualTo(2);
    }

    // ──────────────────────────────────────────────
    // TC7: service_date 범위 쿼리 인자 확인
    // ──────────────────────────────────────────────

    @Test
    void serviceDate_2026_05_03이면_쿼리_인자가_당일_새벽4시_범위이다() {
        when(subwayDataService.findArrivalCandidatesInRange(any(), any()))
                .thenReturn(List.of());

        service.deriveForDate(LocalDate.of(2026, 5, 3));

        verify(subwayDataService).findArrivalCandidatesInRange(
                "2026-05-03 04:00:00",
                "2026-05-04 04:00:00");
    }

    // ──────────────────────────────────────────────
    // TC8: 빈 raw → 이벤트 없음, 삭제는 호출
    // ──────────────────────────────────────────────

    @Test
    void raw가_없으면_빈_이벤트로_저장되고_삭제는_호출된다() {
        when(subwayDataService.findArrivalCandidatesInRange(any(), any()))
                .thenReturn(List.of());
        when(subwayDataService.saveAllArrivalEvents(any()))
                .thenReturn(List.of());

        int result = service.deriveForDate(LocalDate.of(2026, 5, 3));

        verify(subwayDataService).deleteArrivalEventsByServiceDate(LocalDate.of(2026, 5, 3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubwayArrivalEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(subwayDataService).saveAllArrivalEvents(captor.capture());
        assertThat(captor.getValue()).isEmpty();

        assertThat(result).isEqualTo(0);
    }

    // ──────────────────────────────────────────────
    // TC9: destinationId/Name 모두 null → destinationKey = "UNKNOWN"
    // ──────────────────────────────────────────────

    @Test
    void destinationId와_Name이_모두_null이면_destinationKey는_UNKNOWN이다() {
        SubwayArrivalRaw raw1 = rawWithDest("1002", "S1", "내선", "T1",
                "2026-05-03 10:00:00", null, null);

        when(subwayDataService.findArrivalCandidatesInRange(any(), any()))
                .thenReturn(List.of(raw1));
        stubSaveReturnsInput();

        service.deriveForDate(LocalDate.of(2026, 5, 3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubwayArrivalEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(subwayDataService).saveAllArrivalEvents(captor.capture());

        SubwayArrivalEvent event = captor.getValue().get(0);
        assertThat(event.getDestinationKey()).isEqualTo("UNKNOWN");
    }
}
