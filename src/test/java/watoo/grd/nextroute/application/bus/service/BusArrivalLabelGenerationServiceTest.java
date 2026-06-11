package watoo.grd.nextroute.application.bus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.dto.BusArrivalCandidateLabelRow;
import watoo.grd.nextroute.application.bus.dto.BusPositionLabelRow;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalLabelEvent;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusArrivalLabelGenerationServiceTest {

    static final LocalDate SERVICE_DATE = LocalDate.of(2026, 6, 10);
    static final LocalDateTime DAY_START = SERVICE_DATE.atTime(4, 0);
    static final String ROUTE = "100100017";
    static final String STOP_ID = "108000012";
    static final int SEQ = 30;
    static final String VEHICLE_ID = "108045586";
    static final String SECTION_ID = "9999001";
    static final String LIFECYCLE = "lc-test-001";

    @Mock BusDataService busDataService;

    BusArrivalLabelGenerationService service;

    final AtomicLong idSeq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        service = new BusArrivalLabelGenerationService(busDataService);
        service.chunkSize = 2000;
        service.correctionWindowMinutes = 10;
        lenient().when(busDataService.saveAllLabelEvents(any())).thenAnswer(inv -> inv.getArgument(0));
        // 기본: 단일 ROUTE
        lenient().when(busDataService.findCandidateRouteIdsByFinalizedAtBetween(any(), any()))
                .thenReturn(List.of(ROUTE));
        lenient().when(busDataService.findRouteStops(ROUTE)).thenReturn(List.of());
        lenient().when(busDataService.findPositionLabelRowsByRoute(anyString(), any(), any()))
                .thenReturn(List.of());
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private BusArrivalCandidateLabelRow candidate(String vehicleId, String stopId, Integer seq,
                                                  String dataTimestamp, Integer predictTime) {
        return candidate(LIFECYCLE, vehicleId, stopId, seq, dataTimestamp, predictTime);
    }

    private BusArrivalCandidateLabelRow candidate(String lifecycleId, String vehicleId, String stopId,
                                                  Integer seq, String dataTimestamp, Integer predictTime) {
        return new BusArrivalCandidateLabelRow(
                idSeq.getAndIncrement(), lifecycleId, ROUTE, vehicleId, vehicleId, "VEH_ID",
                stopId, seq, 1, "4분 후", dataTimestamp, predictTime);
    }

    private BusPositionLabelRow position(String vehicleId, String sectionId, Integer sectionOrder,
                                         String dataTm, LocalDateTime collectedAt) {
        return new BusPositionLabelRow(
                idSeq.getAndIncrement(), vehicleId, "서울70아1234", sectionId, sectionOrder,
                "1", dataTm, collectedAt);
    }

    private BusRouteStop routeStop(String sectionId, String stopId, Integer seq) {
        return BusRouteStop.builder()
                .routeId(ROUTE)
                .sectionId(sectionId)
                .stopId(stopId)
                .seq(seq)
                .build();
    }

    private void stubCandidates(List<BusArrivalCandidateLabelRow> candidates) {
        when(busDataService.findCandidateLabelRowsByRoute(eq(ROUTE), any(), any()))
                .thenReturn(candidates);
    }

    private void stubPositions(List<BusPositionLabelRow> positions) {
        when(busDataService.findPositionLabelRowsByRoute(eq(ROUTE), any(), any()))
                .thenReturn(positions);
    }

    private void stubRouteStops(BusRouteStop... stops) {
        when(busDataService.findRouteStops(ROUTE)).thenReturn(List.of(stops));
    }

    @SuppressWarnings("unchecked")
    private List<BusArrivalLabelEvent> captureSaved() {
        ArgumentCaptor<List<BusArrivalLabelEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(busDataService, atLeastOnce()).saveAllLabelEvents(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    // ── TC1: API-only candidate → ARRIVAL_API_ETA/medium ─────────────────────

    @Test
    void TC1_position_매칭없으면_ARRIVAL_API_ETA_medium_라벨이_생성된다() {
        stubCandidates(List.of(candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610050000", 120)));

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved).hasSize(1);
        BusArrivalLabelEvent ev = saved.get(0);
        assertThat(ev.getLabelSource()).isEqualTo(BusArrivalLabelEvent.SOURCE_ARRIVAL_API_ETA);
        assertThat(ev.getLabelConfidence()).isEqualTo(BusArrivalLabelEvent.CONFIDENCE_MEDIUM);
        assertThat(ev.isExcludedFromTraining()).isFalse();
        assertThat(ev.getApiEstimatedArrivalAt())
                .isEqualTo(LocalDateTime.of(2026, 6, 10, 5, 2, 0));
        assertThat(ev.getArrivalLifecycleId()).isEqualTo(LIFECYCLE);
    }

    // ── TC2: position visit 매칭 → POSITION_STOP_FLAG_CORRECTED ──────────────

    @Test
    void TC2_position_stop_flag_매칭시_POSITION_STOP_FLAG_CORRECTED_라벨이_생성된다() {
        stubCandidates(List.of(candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610050000", 120)));
        LocalDateTime collectedAt = LocalDateTime.of(2026, 6, 10, 5, 1, 10);
        stubPositions(List.of(position(VEHICLE_ID, SECTION_ID, 28, "20260610050100", collectedAt)));
        stubRouteStops(routeStop(SECTION_ID, STOP_ID, SEQ));

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved).hasSize(1);
        BusArrivalLabelEvent ev = saved.get(0);
        assertThat(ev.getLabelSource()).isEqualTo(BusArrivalLabelEvent.SOURCE_POSITION_STOP_FLAG_CORRECTED);
        assertThat(ev.getLabelConfidence()).isEqualTo(BusArrivalLabelEvent.CONFIDENCE_HIGH_PROVISIONAL);
        assertThat(ev.isExcludedFromTraining()).isFalse();
        assertThat(ev.getCorrectedArrivalAt()).isEqualTo(LocalDateTime.of(2026, 6, 10, 5, 1, 0));
        assertThat(ev.getLabelArrivalAt()).isEqualTo(ev.getCorrectedArrivalAt());
        assertThat(ev.getSectionId()).isEqualTo(SECTION_ID);
        assertThat(ev.getPositionRawIds()).isNotNull();
    }

    // ── TC3: predict_time null → INVALID_API_ETA, row 보존 ───────────────────

    @Test
    void TC3_predict_time_null이면_INVALID_API_ETA로_excluded_row_보존된다() {
        stubCandidates(List.of(candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610050000", null)));

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved).hasSize(1);
        BusArrivalLabelEvent ev = saved.get(0);
        assertThat(ev.isExcludedFromTraining()).isTrue();
        assertThat(ev.getExcludeReason()).isEqualTo(BusArrivalLabelEvent.EXCLUDE_INVALID_API_ETA);
        assertThat(ev.getLabelArrivalAt()).isNull();
    }

    // ── TC4: dwell — stop_flag 묶음 2개 → dwell_seconds, 1개 → null ───────────

    @Test
    void TC4_정차_snapshot_2개면_dwell_seconds_계산된다() {
        stubCandidates(List.of(candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610050000", 120)));
        LocalDateTime t1 = LocalDateTime.of(2026, 6, 10, 5, 1, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 6, 10, 5, 2, 0);
        stubPositions(List.of(
                position(VEHICLE_ID, SECTION_ID, 28, "20260610050100", t1.plusSeconds(5)),
                position(VEHICLE_ID, SECTION_ID, 28, "20260610050200", t2.plusSeconds(5))));
        stubRouteStops(routeStop(SECTION_ID, STOP_ID, SEQ));

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved).hasSize(1);
        BusArrivalLabelEvent ev = saved.get(0);
        assertThat(ev.getDwellSeconds()).isEqualTo(60);
        assertThat(ev.getDepartedAt()).isNotNull();
    }

    @Test
    void TC4b_정차_snapshot_1개면_dwell_null이다() {
        stubCandidates(List.of(candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610050000", 120)));
        LocalDateTime t1 = LocalDateTime.of(2026, 6, 10, 5, 1, 0);
        stubPositions(List.of(position(VEHICLE_ID, SECTION_ID, 28, "20260610050100", t1.plusSeconds(5))));
        stubRouteStops(routeStop(SECTION_ID, STOP_ID, SEQ));

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved.get(0).getDwellSeconds()).isNull();
        assertThat(saved.get(0).getDepartedAt()).isNull();
    }

    // ── TC5: trip 분리 — section_order 급감으로 다른 trip ─────────────────────

    @Test
    void TC5_section_order_급감_후_같은_section_id_재방문은_다른_trip으로_분리된다() {
        stubCandidates(List.of(candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610052800", 120)));

        // 1st trip 정차: 05:01, section_order 28
        LocalDateTime t1 = LocalDateTime.of(2026, 6, 10, 5, 1, 0);
        BusPositionLabelRow pFirst = position(VEHICLE_ID, SECTION_ID, 28, "20260610050100", t1.plusSeconds(5));
        // trip reset: 05:10, section_order 2 (급감) + 다른 section_id (visit 안 됨, 경계만)
        LocalDateTime t2 = LocalDateTime.of(2026, 6, 10, 5, 10, 0);
        BusPositionLabelRow pReset = position(VEHICLE_ID, "other-section", 2, "20260610051000", t2.plusSeconds(5));
        // 2nd trip 정차: 05:29, section_order 28 → apiEta 05:30 매칭 대상
        LocalDateTime t3 = LocalDateTime.of(2026, 6, 10, 5, 29, 0);
        BusPositionLabelRow pSecond = position(VEHICLE_ID, SECTION_ID, 28, "20260610052900", t3.plusSeconds(5));

        stubPositions(List.of(pFirst, pReset, pSecond));
        stubRouteStops(routeStop(SECTION_ID, STOP_ID, SEQ));

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved).hasSize(1);
        // 2nd trip visit(05:29)이 apiEta(05:30)와 1분차로 매칭
        assertThat(saved.get(0).getLabelSource())
                .isEqualTo(BusArrivalLabelEvent.SOURCE_POSITION_STOP_FLAG_CORRECTED);
        assertThat(saved.get(0).getCorrectedArrivalAt())
                .isEqualTo(LocalDateTime.of(2026, 6, 10, 5, 29, 0));
    }

    // ── TC6: correction window 밖 → API ETA fallback ─────────────────────────

    @Test
    void TC6_correction_window_초과_visit은_API_ETA_fallback을_사용한다() {
        stubCandidates(List.of(candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610050000", 120)));
        LocalDateTime posTime = LocalDateTime.of(2026, 6, 10, 5, 20, 0);
        stubPositions(List.of(position(VEHICLE_ID, SECTION_ID, 28, "20260610052000", posTime.plusSeconds(5))));
        stubRouteStops(routeStop(SECTION_ID, STOP_ID, SEQ));

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved.get(0).getLabelSource())
                .isEqualTo(BusArrivalLabelEvent.SOURCE_ARRIVAL_API_ETA);
    }

    // ── TC7: deleteByServiceDate 선행 호출 (idempotency) ─────────────────────

    @Test
    void TC7_generateForDate_호출시_deleteByServiceDate가_먼저_호출된다() {
        when(busDataService.findCandidateRouteIdsByFinalizedAtBetween(any(), any()))
                .thenReturn(List.of());

        service.generateForDate(SERVICE_DATE);

        verify(busDataService).deleteLabelEventsByServiceDate(SERVICE_DATE);
    }

    // ── TC8: 청크 분할 saveAll 호출 횟수 ─────────────────────────────────────

    @Test
    void TC8_chunkSize_초과시_saveAllLabelEvents가_여러번_호출된다() {
        service.chunkSize = 2;
        stubCandidates(List.of(
                candidate("lc-001", "V001", STOP_ID, SEQ, "20260610050000", 60),
                candidate("lc-002", "V002", STOP_ID, SEQ, "20260610050000", 120),
                candidate("lc-003", "V003", STOP_ID, SEQ, "20260610050000", 180)));

        service.generateForDate(SERVICE_DATE);

        // chunkSize=2, 3건 → chunk 2건(save#1) + route 끝 남은 1건(save#2)
        verify(busDataService, times(2)).saveAllLabelEvents(any());
    }

    // ── TC9: data_tm 파싱 ─────────────────────────────────────────────────────

    @Test
    void TC9_NUM14_data_tm은_파싱된다() {
        assertThat(BusArrivalLabelGenerationService.parseDataTm("20260610130001"))
                .isEqualTo(LocalDateTime.of(2026, 6, 10, 13, 0, 1));
    }

    @Test
    void TC9b_ISO_T_data_tm은_파싱된다() {
        assertThat(BusArrivalLabelGenerationService.parseDataTm("2026-06-10T13:00:01"))
                .isEqualTo(LocalDateTime.of(2026, 6, 10, 13, 0, 1));
    }

    @Test
    void TC9b2_mkTm_공백_밀리초_형식이_파싱된다() {
        // 서울 도착 API mkTm 실제 형식 (bus_arrival_candidate_raw.data_timestamp)
        assertThat(BusArrivalLabelGenerationService.parseDataTm("2026-06-10 10:07:02.0"))
                .isEqualTo(LocalDateTime.of(2026, 6, 10, 10, 7, 2));
        assertThat(BusArrivalLabelGenerationService.parseDataTm("2026-06-10 13:00:01"))
                .isEqualTo(LocalDateTime.of(2026, 6, 10, 13, 0, 1));
        assertThat(BusArrivalLabelGenerationService.parseDataTm("2026-06-10 13:00:01.123"))
                .isEqualTo(LocalDateTime.of(2026, 6, 10, 13, 0, 1));
    }

    @Test
    void TC9b3_17자리_밀리초_숫자형식은_앞_14자리로_파싱된다() {
        assertThat(BusArrivalLabelGenerationService.parseDataTm("20260610130001000"))
                .isEqualTo(LocalDateTime.of(2026, 6, 10, 13, 0, 1));
    }

    @Test
    void TC9c_불량_data_tm은_null_반환된다() {
        assertThat(BusArrivalLabelGenerationService.parseDataTm("INVALID")).isNull();
        assertThat(BusArrivalLabelGenerationService.parseDataTm(null)).isNull();
        assertThat(BusArrivalLabelGenerationService.parseDataTm("")).isNull();
        assertThat(BusArrivalLabelGenerationService.parseDataTm("2026")).isNull();
    }

    @Test
    void TC9e_mkTm_형식_candidate가_INVALID_아닌_정상_라벨이_된다() {
        // 회귀: 공백+밀리초 mkTm이 parseDataTm 실패로 전건 INVALID_API_ETA 되던 버그 방지
        stubCandidates(List.of(candidate(VEHICLE_ID, STOP_ID, SEQ, "2026-06-10 05:00:00.0", 120)));

        service.generateForDate(SERVICE_DATE);

        BusArrivalLabelEvent ev = captureSaved().get(0);
        assertThat(ev.isExcludedFromTraining()).isFalse();
        assertThat(ev.getLabelSource()).isEqualTo(BusArrivalLabelEvent.SOURCE_ARRIVAL_API_ETA);
        assertThat(ev.getApiEstimatedArrivalAt()).isEqualTo(LocalDateTime.of(2026, 6, 10, 5, 2, 0));
    }

    @Test
    void TC9d_불량_data_tm_가진_position은_visit_생성에서_제외된다() {
        stubCandidates(List.of(candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610050000", 120)));
        LocalDateTime collected = LocalDateTime.of(2026, 6, 10, 5, 1, 0);
        stubPositions(List.of(position(VEHICLE_ID, SECTION_ID, 28, "BADFORMAT", collected)));
        stubRouteStops(routeStop(SECTION_ID, STOP_ID, SEQ));

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved.get(0).getLabelSource()).isEqualTo(BusArrivalLabelEvent.SOURCE_ARRIVAL_API_ETA);
    }

    // ── TC10: 신선도 가드 ─────────────────────────────────────────────────────

    @Test
    void TC10_data_tm_lag_2분_초과_position은_visit_생성에서_제외된다() {
        stubCandidates(List.of(candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610050000", 120)));
        LocalDateTime dataTmTime = LocalDateTime.of(2026, 6, 10, 5, 1, 0);
        // collected_at = data_tm + 4분 → lag 4분 > 2분 → 제외
        stubPositions(List.of(position(VEHICLE_ID, SECTION_ID, 28, "20260610050100", dataTmTime.plusMinutes(4))));
        stubRouteStops(routeStop(SECTION_ID, STOP_ID, SEQ));

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved.get(0).getLabelSource()).isEqualTo(BusArrivalLabelEvent.SOURCE_ARRIVAL_API_ETA);
    }
}
