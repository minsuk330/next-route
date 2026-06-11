package watoo.grd.nextroute.application.bus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalCandidateRaw;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalLabelEvent;
import watoo.grd.nextroute.domain.bus.entity.BusPositionRaw;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @BeforeEach
    void setUp() {
        service = new BusArrivalLabelGenerationService(busDataService);
        service.chunkSize = 2000;
        service.correctionWindowMinutes = 10;
        lenient().when(busDataService.saveAllLabelEvents(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private BusArrivalCandidateRaw candidate(String vehicleId, String stopId, Integer seq,
                                              String dataTimestamp, Integer predictTime) {
        return BusArrivalCandidateRaw.builder()
                .lifecycleId(LIFECYCLE)
                .routeId(ROUTE)
                .vehicleId(vehicleId)
                .vehicleIdentity(vehicleId)
                .vehicleIdentityType("VEH_ID")
                .stopId(stopId)
                .seq(seq)
                .arrivalOrder(1)
                .arrivalMsg("4분 후")
                .dataTimestamp(dataTimestamp)
                .predictTime(predictTime)
                .finalizedAt(DAY_START.plusHours(1))
                .collectedAt(DAY_START.plusHours(1))
                .build();
    }

    private BusPositionRaw position(String vehicleId, String sectionId, Integer sectionOrder,
                                     String stopFlag, String dataTm, LocalDateTime collectedAt) {
        return BusPositionRaw.builder()
                .routeId(ROUTE)
                .vehicleId(vehicleId)
                .plainNo("서울70아1234")
                .sectionId(sectionId)
                .sectionOrder(sectionOrder)
                .stopFlag(stopFlag)
                .dataTm(dataTm)
                .collectedAt(collectedAt)
                .isRunYn("1")
                .nextStopId("108000013")
                .build();
    }

    private BusRouteStop routeStop(String sectionId, String stopId, Integer seq) {
        return BusRouteStop.builder()
                .routeId(ROUTE)
                .sectionId(sectionId)
                .stopId(stopId)
                .seq(seq)
                .build();
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
        BusArrivalCandidateRaw c = candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610050000", 120);
        when(busDataService.findCandidatesByFinalizedAtBetween(any(), any())).thenReturn(List.of(c));
        when(busDataService.findPositionsByRouteIdAndCollectedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());
        when(busDataService.findRouteStops(ROUTE)).thenReturn(List.of());

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved).hasSize(1);
        BusArrivalLabelEvent ev = saved.get(0);
        assertThat(ev.getLabelSource()).isEqualTo(BusArrivalLabelEvent.SOURCE_ARRIVAL_API_ETA);
        assertThat(ev.getLabelConfidence()).isEqualTo(BusArrivalLabelEvent.CONFIDENCE_MEDIUM);
        assertThat(ev.isExcludedFromTraining()).isFalse();
        // api ETA = 20260610050000 + 120s = 05:02:00
        assertThat(ev.getApiEstimatedArrivalAt())
                .isEqualTo(LocalDateTime.of(2026, 6, 10, 5, 2, 0));
        assertThat(ev.getArrivalLifecycleId()).isEqualTo(LIFECYCLE);
    }

    // ── TC2: position visit 매칭 → POSITION_STOP_FLAG_CORRECTED ──────────────

    @Test
    void TC2_position_stop_flag_매칭시_POSITION_STOP_FLAG_CORRECTED_라벨이_생성된다() {
        // data_timestamp=20260610050000, predict_time=120 → apiEta=05:02:00
        BusArrivalCandidateRaw c = candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610050000", 120);

        // position: 05:01:00 data_tm, stop_flag=1, section_id 매칭
        LocalDateTime collectedAt = LocalDateTime.of(2026, 6, 10, 5, 1, 10);
        BusPositionRaw pos = position(VEHICLE_ID, SECTION_ID, 28, "1", "20260610050100", collectedAt);

        when(busDataService.findCandidatesByFinalizedAtBetween(any(), any())).thenReturn(List.of(c));
        when(busDataService.findPositionsByRouteIdAndCollectedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(pos));
        when(busDataService.findRouteStops(ROUTE))
                .thenReturn(List.of(routeStop(SECTION_ID, STOP_ID, SEQ)));

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
    }

    // ── TC3: predict_time null → INVALID_API_ETA, row 보존 ───────────────────

    @Test
    void TC3_predict_time_null이면_INVALID_API_ETA로_excluded_row_보존된다() {
        BusArrivalCandidateRaw c = candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610050000", null);
        when(busDataService.findCandidatesByFinalizedAtBetween(any(), any())).thenReturn(List.of(c));
        when(busDataService.findPositionsByRouteIdAndCollectedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());
        when(busDataService.findRouteStops(ROUTE)).thenReturn(List.of());

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved).hasSize(1);
        BusArrivalLabelEvent ev = saved.get(0);
        assertThat(ev.isExcludedFromTraining()).isTrue();
        assertThat(ev.getExcludeReason()).isEqualTo(BusArrivalLabelEvent.EXCLUDE_INVALID_API_ETA);
        assertThat(ev.getLabelArrivalAt()).isNull();
    }

    // ── TC4: dwell — stop_flag=1 snapshot 2개 → dwell_seconds, 1개 → null ────

    @Test
    void TC4_stop_flag_snapshot_2개면_dwell_seconds_계산된다() {
        BusArrivalCandidateRaw c = candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610050000", 120);

        LocalDateTime t1 = LocalDateTime.of(2026, 6, 10, 5, 1, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 6, 10, 5, 2, 0);
        BusPositionRaw p1 = position(VEHICLE_ID, SECTION_ID, 28, "1", "20260610050100", t1.plusSeconds(5));
        BusPositionRaw p2 = position(VEHICLE_ID, SECTION_ID, 28, "1", "20260610050200", t2.plusSeconds(5));

        when(busDataService.findCandidatesByFinalizedAtBetween(any(), any())).thenReturn(List.of(c));
        when(busDataService.findPositionsByRouteIdAndCollectedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(p1, p2));
        when(busDataService.findRouteStops(ROUTE))
                .thenReturn(List.of(routeStop(SECTION_ID, STOP_ID, SEQ)));

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved).hasSize(1);
        BusArrivalLabelEvent ev = saved.get(0);
        assertThat(ev.getDwellSeconds()).isEqualTo(60);
        assertThat(ev.getDepartedAt()).isNotNull();
    }

    @Test
    void TC4b_stop_flag_snapshot_1개면_dwell_null이다() {
        BusArrivalCandidateRaw c = candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610050000", 120);
        LocalDateTime t1 = LocalDateTime.of(2026, 6, 10, 5, 1, 0);
        BusPositionRaw p1 = position(VEHICLE_ID, SECTION_ID, 28, "1", "20260610050100", t1.plusSeconds(5));

        when(busDataService.findCandidatesByFinalizedAtBetween(any(), any())).thenReturn(List.of(c));
        when(busDataService.findPositionsByRouteIdAndCollectedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(p1));
        when(busDataService.findRouteStops(ROUTE))
                .thenReturn(List.of(routeStop(SECTION_ID, STOP_ID, SEQ)));

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved.get(0).getDwellSeconds()).isNull();
        assertThat(saved.get(0).getDepartedAt()).isNull();
    }

    // ── TC5: trip 분리 — 회차 후 재방문은 이전 trip visit과 매칭 안 됨 ─────────

    @Test
    void TC5_section_order_급감_후_같은_section_id_재방문은_다른_trip으로_분리된다() {
        // candidate: apiEta=05:30:00 (stopId, seq 기준)
        BusArrivalCandidateRaw c = candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610052800", 120);

        // 1st trip: t=05:01, stop_flag=1, section_id=SECTION_ID
        LocalDateTime t1 = LocalDateTime.of(2026, 6, 10, 5, 1, 0);
        BusPositionRaw pFirstTrip = position(VEHICLE_ID, SECTION_ID, 28, "1",
                "20260610050100", t1.plusSeconds(5));

        // trip reset snapshot: section_order 크게 감소 (28 → 2)
        LocalDateTime t2 = LocalDateTime.of(2026, 6, 10, 5, 10, 0);
        BusPositionRaw pReset = position(VEHICLE_ID, "other-section", 2, "0",
                "20260610051000", t2.plusSeconds(5));

        // 2nd trip: t=05:29, stop_flag=1, section_id=SECTION_ID → apiEta 05:30 기준 매칭 대상
        LocalDateTime t3 = LocalDateTime.of(2026, 6, 10, 5, 29, 0);
        BusPositionRaw pSecondTrip = position(VEHICLE_ID, SECTION_ID, 28, "1",
                "20260610052900", t3.plusSeconds(5));

        when(busDataService.findCandidatesByFinalizedAtBetween(any(), any())).thenReturn(List.of(c));
        when(busDataService.findPositionsByRouteIdAndCollectedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(pFirstTrip, pReset, pSecondTrip));
        when(busDataService.findRouteStops(ROUTE))
                .thenReturn(List.of(routeStop(SECTION_ID, STOP_ID, SEQ)));

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved).hasSize(1);
        // 2nd trip visit(05:29)이 apiEta(05:30)과 1분 차이라 매칭, corrected
        assertThat(saved.get(0).getLabelSource())
                .isEqualTo(BusArrivalLabelEvent.SOURCE_POSITION_STOP_FLAG_CORRECTED);
        assertThat(saved.get(0).getCorrectedArrivalAt())
                .isEqualTo(LocalDateTime.of(2026, 6, 10, 5, 29, 0));
    }

    // ── TC6: correction window 밖 visit → API ETA fallback ───────────────────

    @Test
    void TC6_correction_window_초과_visit은_API_ETA_fallback을_사용한다() {
        // apiEta = 05:02:00, position은 05:20 (18분 차이, window=10분)
        BusArrivalCandidateRaw c = candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610050000", 120);
        LocalDateTime posTime = LocalDateTime.of(2026, 6, 10, 5, 20, 0);
        BusPositionRaw pos = position(VEHICLE_ID, SECTION_ID, 28, "1",
                "20260610052000", posTime.plusSeconds(5));

        when(busDataService.findCandidatesByFinalizedAtBetween(any(), any())).thenReturn(List.of(c));
        when(busDataService.findPositionsByRouteIdAndCollectedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(pos));
        when(busDataService.findRouteStops(ROUTE))
                .thenReturn(List.of(routeStop(SECTION_ID, STOP_ID, SEQ)));

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved.get(0).getLabelSource())
                .isEqualTo(BusArrivalLabelEvent.SOURCE_ARRIVAL_API_ETA);
    }

    // ── TC7: deleteByServiceDate 선행 호출 검증 (idempotency) ─────────────────

    @Test
    void TC7_generateForDate_호출시_deleteByServiceDate가_먼저_호출된다() {
        when(busDataService.findCandidatesByFinalizedAtBetween(any(), any())).thenReturn(List.of());

        service.generateForDate(SERVICE_DATE);

        verify(busDataService).deleteLabelEventsByServiceDate(SERVICE_DATE);
    }

    // ── TC8: 청크 분할 saveAll 호출 횟수 ─────────────────────────────────────

    @Test
    void TC8_chunkSize_초과시_saveAllLabelEvents가_여러번_호출된다() {
        service.chunkSize = 2;

        // candidate 3개 → 1개 route → chunk 분할
        BusArrivalCandidateRaw c1 = candidate("V001", STOP_ID, SEQ, "20260610050000", 60);
        BusArrivalCandidateRaw c2 = candidate("V002", STOP_ID, SEQ, "20260610050000", 60);
        BusArrivalCandidateRaw c3 = candidate("V003", STOP_ID, SEQ, "20260610050000", 60);

        // 서로 다른 lifecycle_id 필요 — builder로 직접 생성
        BusArrivalCandidateRaw cand1 = BusArrivalCandidateRaw.builder()
                .lifecycleId("lc-001").routeId(ROUTE).vehicleId("V001")
                .vehicleIdentity("V001").vehicleIdentityType("VEH_ID")
                .stopId(STOP_ID).seq(SEQ).arrivalOrder(1).arrivalMsg("1분 후")
                .dataTimestamp("20260610050000").predictTime(60)
                .finalizedAt(DAY_START.plusHours(1)).collectedAt(DAY_START.plusHours(1)).build();
        BusArrivalCandidateRaw cand2 = BusArrivalCandidateRaw.builder()
                .lifecycleId("lc-002").routeId(ROUTE).vehicleId("V002")
                .vehicleIdentity("V002").vehicleIdentityType("VEH_ID")
                .stopId(STOP_ID).seq(SEQ).arrivalOrder(1).arrivalMsg("2분 후")
                .dataTimestamp("20260610050000").predictTime(120)
                .finalizedAt(DAY_START.plusHours(1)).collectedAt(DAY_START.plusHours(1)).build();
        BusArrivalCandidateRaw cand3 = BusArrivalCandidateRaw.builder()
                .lifecycleId("lc-003").routeId(ROUTE).vehicleId("V003")
                .vehicleIdentity("V003").vehicleIdentityType("VEH_ID")
                .stopId(STOP_ID).seq(SEQ).arrivalOrder(1).arrivalMsg("3분 후")
                .dataTimestamp("20260610050000").predictTime(180)
                .finalizedAt(DAY_START.plusHours(1)).collectedAt(DAY_START.plusHours(1)).build();

        when(busDataService.findCandidatesByFinalizedAtBetween(any(), any()))
                .thenReturn(List.of(cand1, cand2, cand3));
        when(busDataService.findPositionsByRouteIdAndCollectedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of());
        when(busDataService.findRouteStops(ROUTE)).thenReturn(List.of());

        service.generateForDate(SERVICE_DATE);

        // chunkSize=2, 3건 → 2회 save (2건+1건)
        verify(busDataService, times(2)).saveAllLabelEvents(any());
    }

    // ── TC9: data_tm 파싱 ─────────────────────────────────────────────────────

    @Test
    void TC9_NUM14_data_tm은_파싱된다() {
        LocalDateTime result = BusArrivalLabelGenerationService.parseDataTm("20260610130001");
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 6, 10, 13, 0, 1));
    }

    @Test
    void TC9b_ISO_data_tm은_파싱된다() {
        LocalDateTime result = BusArrivalLabelGenerationService.parseDataTm("2026-06-10T13:00:01");
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 6, 10, 13, 0, 1));
    }

    @Test
    void TC9c_불량_data_tm은_null_반환된다() {
        assertThat(BusArrivalLabelGenerationService.parseDataTm("INVALID")).isNull();
        assertThat(BusArrivalLabelGenerationService.parseDataTm(null)).isNull();
        assertThat(BusArrivalLabelGenerationService.parseDataTm("")).isNull();
    }

    @Test
    void TC9d_불량_data_tm_가진_position은_visit_생성에서_제외된다() {
        BusArrivalCandidateRaw c = candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610050000", 120);
        // data_tm이 불량 → snapshot_at 파싱 실패 → visit 미생성 → API ETA fallback
        LocalDateTime collected = LocalDateTime.of(2026, 6, 10, 5, 1, 0);
        BusPositionRaw badPos = position(VEHICLE_ID, SECTION_ID, 28, "1", "BADFORMAT", collected);

        when(busDataService.findCandidatesByFinalizedAtBetween(any(), any())).thenReturn(List.of(c));
        when(busDataService.findPositionsByRouteIdAndCollectedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(badPos));
        when(busDataService.findRouteStops(ROUTE))
                .thenReturn(List.of(routeStop(SECTION_ID, STOP_ID, SEQ)));

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved.get(0).getLabelSource()).isEqualTo(BusArrivalLabelEvent.SOURCE_ARRIVAL_API_ETA);
    }

    // ── TC10: 신선도 가드 ─────────────────────────────────────────────────────

    @Test
    void TC10_data_tm_lag_2분_초과_position은_visit_생성에서_제외된다() {
        BusArrivalCandidateRaw c = candidate(VEHICLE_ID, STOP_ID, SEQ, "20260610050000", 120);
        // data_tm=05:01:00, collected_at=05:05:00 → lag=4분 > 2분 → 제외
        LocalDateTime dataTmTime = LocalDateTime.of(2026, 6, 10, 5, 1, 0);
        LocalDateTime collectedAt = dataTmTime.plusMinutes(4);
        BusPositionRaw stalePos = position(VEHICLE_ID, SECTION_ID, 28, "1",
                "20260610050100", collectedAt);

        when(busDataService.findCandidatesByFinalizedAtBetween(any(), any())).thenReturn(List.of(c));
        when(busDataService.findPositionsByRouteIdAndCollectedAtBetween(anyString(), any(), any()))
                .thenReturn(List.of(stalePos));
        when(busDataService.findRouteStops(ROUTE))
                .thenReturn(List.of(routeStop(SECTION_ID, STOP_ID, SEQ)));

        service.generateForDate(SERVICE_DATE);

        List<BusArrivalLabelEvent> saved = captureSaved();
        assertThat(saved.get(0).getLabelSource()).isEqualTo(BusArrivalLabelEvent.SOURCE_ARRIVAL_API_ETA);
    }
}
