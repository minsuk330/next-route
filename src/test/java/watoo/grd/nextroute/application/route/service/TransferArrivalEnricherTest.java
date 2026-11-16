package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.route.config.MlPredictorProperties;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.dto.*;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlFeatureVector;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPrediction;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPredictionStatus;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.observability.RouteSearchContext;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.entity.BusStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferArrivalEnricherTest {

    @Mock SearchTimeBusQueryPort busPort;
    @Mock MlArrivalPredictorPort mlPort;
    @Mock BusStopRepository stopRepo;
    @Mock BusRouteRepository routeRepo;
    @Mock BusRouteStopRepository routeStopRepo;
    @Mock PredictionSupportService predictionSupport;
    @Mock SearchRegionalArrivalService regional;

    TransferArrivalProperties props = new TransferArrivalProperties();
    MlPredictorProperties mlProps = new MlPredictorProperties();
    TransferStopResolver resolver;
    MlFeatureVectorBuilder featureBuilder;
    TransferArrivalEnricher enricher;

    static final Instant NOW = Instant.parse("2026-06-06T04:00:00Z"); // KST 13:00

    // deadline 미초과(NOW 고정 clock — clock.instant()는 항상 NOW, deadline=NOW+1500ms 안 넘음)
    java.time.Clock clock = java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        resolver = new TransferStopResolver(stopRepo, routeRepo, routeStopRepo);
        featureBuilder = new MlFeatureVectorBuilder(d -> false, org.mockito.Mockito.mock(WeatherSnapshotService.class));
        enricher = new TransferArrivalEnricher(props, mlProps, busPort, mlPort,
                resolver, featureBuilder, predictionSupport, clock, regional);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private static BusQueryResult<BusArrivalInfo> arrOk(List<BusArrivalInfo> l) { return BusQueryResult.ok(l); }
    private static BusQueryResult<BusPositionInfo> posOk(List<BusPositionInfo> l) { return BusQueryResult.ok(l); }

    private SubPathResult busSubPath(String startLocalStationID, String busLocalBlID, String busNo) {
        return new SubPathResult(
                2, 10, 1500.0,
                List.of(new LaneResult("360", busNo, null, 1, null, busLocalBlID, null)),
                Collections.emptyList(),
                "출발", "도착",
                127.0, 37.5, 127.01, 37.51,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null,
                startLocalStationID, null, null, null, null, null, null, null, null, null, null);
    }

    private SubPathResult walkSubPath(int sectionTime) {
        return new SubPathResult(
                3, sectionTime, 100.0,
                Collections.emptyList(), Collections.emptyList(),
                "A", "B", 127.0, 37.5, 127.01, 37.51,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private RouteSearchResult resultWith(List<SubPathResult> subPaths) {
        PathInfo info = new PathInfo(3600, 1000, 0, 0, "출발", "도착", null);
        return new RouteSearchResult(0, 1, 0, 0, 0,
                List.of(new PathResult(1, info, subPaths, Collections.emptyList())));
    }

    private BusArrivalInfo arrivalInfo(String routeId, String mkTm, int predictTime1) {
        return arrivalInfo(routeId, mkTm, predictTime1, 600);
    }

    /** predictTime2: bus2 예측시간(초). mkTm 기준. -1 이면 null로 설정. */
    private BusArrivalInfo arrivalInfo(String routeId, String mkTm, int predictTime1, int predictTime2) {
        return arrivalInfo(routeId, mkTm, predictTime1, predictTime2, "1분", "msg2", null);
    }

    /** 운행종료 판정용 — 도착 메시지 두 개와 정류장 첫차 시각({@code yyyyMMddHHmmss}). 예측 초는 없다. */
    private BusArrivalInfo endedInfo(String routeId, String msg1, String msg2, String firstTm) {
        return arrivalInfo(routeId, "2026-06-06 12:59:50.0", -1, -1, msg1, msg2, firstTm);
    }

    private BusArrivalInfo arrivalInfo(String routeId, String mkTm, int predictTime1, int predictTime2,
                                       String msg1, String msg2, String firstTm) {
        Integer pt1 = predictTime1 < 0 ? null : predictTime1;
        Integer pt2 = predictTime2 < 0 ? null : predictTime2;
        return new BusArrivalInfo(
                // 공통 15
                routeId, "360", "360", "1111", null, "정류장",
                5, null, 3, 10,
                mkTm,
                null, null, firstTm, null,
                // 버스1 기본 9
                msg1, "v1", null, 0, 3, "현재정류장", "0", "0", "0",
                // 버스1 예측+계수 8
                pt1, null, null, null,
                null, null, null, null,
                // 버스1 구간+혼잡 6 (sectionTime1 = 도착 ETA 소스)
                pt1, null,
                null, null, null, null,
                // 버스1 다음정류소 4
                null, null, null, null,
                // 버스1 주요정류소 9
                null, null, null,
                null, null, null,
                null, null, null,
                // 버스2 기본 9
                msg2, "v2", null, 0, 5, "다음정류장", "0", "0", "0",
                // 버스2 예측+계수 8
                pt2, null, null, null,
                null, null, null, null,
                // 버스2 구간+혼잡 6 (sectionTime2 = 도착 ETA 소스)
                pt2, null,
                null, null, null, null,
                // 버스2 다음정류소 4
                null, null, null, null,
                // 버스2 주요정류소 9
                null, null, null,
                null, null, null,
                null, null, null
        );
    }

    private BusStop busStop(String stopId) {
        return BusStop.builder().stopId(stopId).arsId("01234").stopName("정류장").build();
    }

    private BusRoute busRoute(String routeId, String routeName) {
        return BusRoute.builder().routeId(routeId).routeName(routeName).build();
    }

    private BusRouteStop routeStop(String routeId, String stopId, int seq) {
        return BusRouteStop.builder().routeId(routeId).stopId(stopId).seq(seq).build();
    }

    // ── 테스트 ────────────────────────────────────────────────────────────

    // ── 운행종료 경로 숨김(#400) ──────────────────────────────────────────

    private SubPathResult busSubPathLanes(String stopId, List<LaneResult> lanes) {
        SubPathResult one = busSubPath(stopId, "x", "x");
        return new SubPathResult(one.trafficType(), one.sectionTime(), one.distance(), lanes, one.stations(),
                one.startName(), one.endName(), one.startX(), one.startY(), one.endX(), one.endY(),
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                stopId, null, null, null, null, null, null, null, null, null, null);
    }

    private void stubRoute(String routeId, String name, BusArrivalInfo arrival) {
        when(stopRepo.findByStopId("1111")).thenReturn(Optional.of(busStop("1111")));
        when(routeRepo.findByRouteId(routeId)).thenReturn(Optional.of(busRoute(routeId, name)));
        when(routeStopRepo.findByRouteIdAndStopId(routeId, "1111")).thenReturn(List.of(routeStop(routeId, "1111", 5)));
        lenient().when(busPort.getArrInfoByStop(eq("1111"), eq(routeId), eq(5), anyLong())).thenReturn(arrOk(List.of(arrival)));
        lenient().when(busPort.getArrInfoByStop("1111", routeId, 5)).thenReturn(arrOk(List.of(arrival)));
    }

    @Test
    void TC_운행종료_구간을_지나는_경로는_빠지고_나머지_순서는_그대로() {
        props.setEnabled(true);
        lenient().when(predictionSupport.support(any())).thenReturn(PredictionSupportService.Support.UNSUPPORTED);
        stubRoute("100100750", "750B", endedInfo("100100750", "운행종료", "운행종료", "20260606043500"));
        stubRoute("100100360", "360", arrivalInfo("100100360", "2026-06-06 12:59:50.0", 300));
        PathInfo info = new PathInfo(30, 1000, 0, 0, "출발", "도착", null);
        PathResult ended = new PathResult(2, info, List.of(busSubPath("1111", "100100750", "750B")), List.of());
        PathResult alive = new PathResult(2, info, List.of(busSubPath("1111", "100100360", "360")), List.of());
        RouteSearchResult input = new RouteSearchResult(0, 2, 0, 0, 0, List.of(ended, alive));

        RouteSearchResult out = enricher.enrich(input, NOW, RouteSearchContext.start(5000));

        assertThat(out.paths()).hasSize(1);
        assertThat(out.paths().get(0).subPaths().get(0).lanes().get(0).busNo()).isEqualTo("360");
        assertThat(out.busCount()).isEqualTo(1);
    }

    @Test
    void TC_도착예측_복사본이_trainKind_를_보존한다() {
        // 버스 구간에 넣은 값은 복사 경로 확인용 표지다(subway-express S2 — 도착예측을 실은 복사본이 새 칸을 흘리지 않는다).
        props.setEnabled(true);
        lenient().when(predictionSupport.support(any())).thenReturn(PredictionSupportService.Support.UNSUPPORTED);
        stubRoute("100100360", "360", arrivalInfo("100100360", "2026-06-06 12:59:50.0", 300));
        SubPathResult bus = watoo.grd.nextroute.application.journey.service.SubPathFixture.withTrainKind(
                busSubPath("1111", "100100360", "360"), "MARK");

        RouteSearchResult out = enricher.enrich(resultWith(List.of(bus)), NOW, RouteSearchContext.start(5000));

        SubPathResult enriched = out.paths().get(0).subPaths().get(0);
        assertThat(enriched.transferArrivals()).isNotEmpty();
        assertThat(enriched.trainKind()).isEqualTo("MARK");
    }

    @Test
    void TC_같은_정류장_노선_하나라도_살아있으면_남긴다() {
        props.setEnabled(true);
        lenient().when(predictionSupport.support(any())).thenReturn(PredictionSupportService.Support.UNSUPPORTED);
        stubRoute("100100750", "750B", endedInfo("100100750", "운행종료", "운행종료", "20260606043500"));
        stubRoute("100100360", "360", arrivalInfo("100100360", "2026-06-06 12:59:50.0", 300));
        SubPathResult twoLanes = busSubPathLanes("1111", List.of(
                new LaneResult("750B", "750B", null, 1, null, "100100750", null),
                new LaneResult("360", "360", null, 1, null, "100100360", null)));

        RouteSearchResult out = enricher.enrich(resultWith(List.of(twoLanes)), NOW, RouteSearchContext.start(5000));

        assertThat(out.paths()).hasSize(1);
    }

    @Test
    void TC_첫차_전_운행종료는_곧_다니므로_남긴다() {
        props.setEnabled(true);
        lenient().when(predictionSupport.support(any())).thenReturn(PredictionSupportService.Support.UNSUPPORTED);
        // NOW = KST 13:00, 정류장 첫차 13:05, 사용자는 걸어서 15분 뒤 도착 → 그 버스는 탈 수 있다
        stubRoute("100100750", "750B", endedInfo("100100750", "운행종료", null, "20260606130500"));

        RouteSearchResult out = enricher.enrich(resultWith(List.of(walkSubPath(15), busSubPath("1111", "100100750", "750B"))),
                NOW, RouteSearchContext.start(5000));

        assertThat(out.paths()).hasSize(1);
    }

    @Test
    void TC_검색_예산_없는_보강은_숨기지_않는다() {
        props.setEnabled(true);
        lenient().when(predictionSupport.support(any())).thenReturn(PredictionSupportService.Support.UNSUPPORTED);
        when(stopRepo.findByStopId("1111")).thenReturn(Optional.of(busStop("1111")));
        when(routeRepo.findByRouteId("100100750")).thenReturn(Optional.of(busRoute("100100750", "750B")));
        when(routeStopRepo.findByRouteIdAndStopId("100100750", "1111")).thenReturn(List.of(routeStop("100100750", "1111", 5)));
        lenient().when(busPort.getArrInfoByStop(eq("1111"), eq("100100750"), eq(5), anyLong()))
                .thenReturn(arrOk(List.of(endedInfo("100100750", "운행종료", "운행종료", "20260606043500"))));
        lenient().when(busPort.getArrInfoByStop("1111", "100100750", 5))
                .thenReturn(arrOk(List.of(endedInfo("100100750", "운행종료", "운행종료", "20260606043500"))));

        RouteSearchResult out = enricher.enrich(resultWith(List.of(busSubPath("1111", "100100750", "750B"))), NOW);

        assertThat(out.paths()).hasSize(1);
    }

    @Test
    void TC_보강_뒤에도_계획_시각이_남는다() {
        props.setEnabled(false);
        Instant board = Instant.parse("2026-06-06T04:10:00Z");
        SubPathResult planned = busSubPath("1111", "route1", "360")
                .withPlanned(board, board.plusSeconds(900), 180);

        SubPathResult sp = enricher.enrich(resultWith(List.of(planned)), NOW).paths().get(0).subPaths().get(0);

        assertThat(sp.transferArrivals()).hasSize(1);
        assertThat(sp.plannedBoardAt()).isEqualTo(board);
        assertThat(sp.plannedAlightAt()).isEqualTo(board.plusSeconds(900));
        assertThat(sp.plannedWaitSeconds()).isEqualTo(180);
    }

    @Test
    void TC_disabled_모든_lane에_DISABLED_부착() {
        props.setEnabled(false);
        RouteSearchResult input = resultWith(List.of(busSubPath("1111", "route1", "360")));

        RouteSearchResult result = enricher.enrich(input, NOW);

        SubPathResult sp = result.paths().get(0).subPaths().get(0);
        assertThat(sp.transferArrivals()).hasSize(1);
        assertThat(sp.transferArrivals().get(0).status()).isEqualTo(TransferArrival.Status.DISABLED);
        assertThat(sp.transferArrivals().get(0).source()).isEqualTo(TransferArrival.Source.NONE);
        verifyNoInteractions(busPort, mlPort);
    }

    @Test
    void TC_stopId_매핑_실패_STOP_MAPPING_FAILED() {
        props.setEnabled(true);
        when(stopRepo.findByStopId(any())).thenReturn(Optional.empty());
        RouteSearchResult input = resultWith(List.of(busSubPath("9999", "route1", "360")));

        RouteSearchResult result = enricher.enrich(input, NOW);

        SubPathResult sp = result.paths().get(0).subPaths().get(0);
        assertThat(sp.transferArrivals().get(0).status()).isEqualTo(TransferArrival.Status.STOP_MAPPING_FAILED);
        verifyNoInteractions(busPort);
    }

    @Test
    void TC_stop_차단시_NO_VEHICLE아닌_BLOCKED() {
        // 빈 응답을 NO_VEHICLE로 오분류하지 않고 BLOCKED로 구분(리뷰 핵심)
        props.setEnabled(true);
        when(stopRepo.findByStopId("1111")).thenReturn(Optional.of(busStop("1111")));
        when(routeRepo.findByRouteId("100100360")).thenReturn(Optional.of(busRoute("100100360", "360")));
        when(routeStopRepo.findByRouteIdAndStopId("100100360", "1111"))
                .thenReturn(List.of(routeStop("100100360", "1111", 5)));
        when(busPort.getArrInfoByStop("1111", "100100360", 5)).thenReturn(BusQueryResult.blocked());

        RouteSearchResult input = resultWith(List.of(busSubPath("1111", "100100360", "360")));
        RouteSearchResult result = enricher.enrich(input, NOW);

        TransferArrival ta = result.paths().get(0).subPaths().get(0).transferArrivals().get(0);
        assertThat(ta.status()).isEqualTo(TransferArrival.Status.BLOCKED);
    }

    @Test
    void TC_stop_제한시_LIMITED() {
        props.setEnabled(true);
        when(stopRepo.findByStopId("1111")).thenReturn(Optional.of(busStop("1111")));
        when(routeRepo.findByRouteId("100100360")).thenReturn(Optional.of(busRoute("100100360", "360")));
        when(routeStopRepo.findByRouteIdAndStopId("100100360", "1111"))
                .thenReturn(List.of(routeStop("100100360", "1111", 5)));
        when(busPort.getArrInfoByStop("1111", "100100360", 5)).thenReturn(BusQueryResult.limited());

        RouteSearchResult input = resultWith(List.of(busSubPath("1111", "100100360", "360")));
        RouteSearchResult result = enricher.enrich(input, NOW);

        TransferArrival ta = result.paths().get(0).subPaths().get(0).transferArrivals().get(0);
        assertThat(ta.status()).isEqualTo(TransferArrival.Status.LIMITED);
    }

    @Test
    void TC_position_제한시_NO_VEHICLE아닌_LIMITED() {
        props.setEnabled(true);
        mlProps.setEnabled(true);
        when(predictionSupport.support("100100360")).thenReturn(PredictionSupportService.Support.SUPPORTED);
        String routeId = "100100360", stopId = "1111";

        when(stopRepo.findByStopId(stopId)).thenReturn(Optional.of(busStop(stopId)));
        when(routeRepo.findByRouteId(routeId)).thenReturn(Optional.of(busRoute(routeId, "360")));
        when(routeStopRepo.findByRouteIdAndStopId(routeId, stopId))
                .thenReturn(List.of(routeStop(routeId, stopId, 5)));
        // 버스 이미 지나감 → ML 분기. position은 LIMITED
        when(busPort.getArrInfoByStop(stopId, routeId, 5)).thenReturn(arrOk(List.of(arrivalInfo(routeId, "20260606125500", 60, 60))));
        when(busPort.getBusPosByRtid(routeId)).thenReturn(BusQueryResult.limited());

        RouteSearchResult input = resultWith(List.of(busSubPath(stopId, routeId, "360")));
        RouteSearchResult result = enricher.enrich(input, NOW);

        TransferArrival ta = result.paths().get(0).subPaths().get(0).transferArrivals().get(0);
        assertThat(ta.status()).isEqualTo(TransferArrival.Status.LIMITED);
        verifyNoInteractions(mlPort); // position 못 받아 ML 호출 안 함
    }

    @Test
    void TC_routeId_매핑_실패_UNSUPPORTED_ROUTE() {
        props.setEnabled(true);
        when(stopRepo.findByStopId("1111")).thenReturn(Optional.of(busStop("1111")));
        when(routeRepo.findByRouteId(any())).thenReturn(Optional.empty());
        when(routeRepo.findByRouteNameIn(any())).thenReturn(List.of());
        // 서울 노선 id 모양(DB 엔 없음) — id 조회와 번호 폴백이 둘 다 실패하는 경로. 다른 지역 id 는 폴백 자체를 안 한다.
        RouteSearchResult input = resultWith(List.of(busSubPath("1111", "100199999", "unknownBus")));

        RouteSearchResult result = enricher.enrich(input, NOW);

        SubPathResult sp = result.paths().get(0).subPaths().get(0);
        assertThat(sp.transferArrivals().get(0).status()).isEqualTo(TransferArrival.Status.UNSUPPORTED_ROUTE);
    }

    @Test
    void TC_REALTIME_AVAILABLE_버스_도착예정() {
        props.setEnabled(true);
        String routeId = "100100360";
        String stopId = "1111";

        when(stopRepo.findByStopId(stopId)).thenReturn(Optional.of(busStop(stopId)));
        when(routeRepo.findByRouteId(routeId)).thenReturn(Optional.of(busRoute(routeId, "360")));
        when(routeStopRepo.findByRouteIdAndStopId(routeId, stopId))
                .thenReturn(List.of(routeStop(routeId, stopId, 5)));

        // mkTm = KST 13:00:00, predictTime1 = 300s → 13:05:00 KST
        // searchStartedAt = 13:00:00 KST → user arrives in 0s
        // realtimeAt(13:05) >= userAt(13:00) → REALTIME
        String mkTm = "20260606130000"; // KST
        BusArrivalInfo ai = arrivalInfo(routeId, mkTm, 300);
        when(busPort.getArrInfoByStop(stopId, routeId, 5)).thenReturn(arrOk(List.of(ai)));

        RouteSearchResult input = resultWith(List.of(busSubPath(stopId, routeId, "360")));
        RouteSearchResult result = enricher.enrich(input, NOW);

        TransferArrival ta = result.paths().get(0).subPaths().get(0).transferArrivals().get(0);
        assertThat(ta.source()).isEqualTo(TransferArrival.Source.REALTIME);
        assertThat(ta.status()).isEqualTo(TransferArrival.Status.AVAILABLE);
        assertThat(ta.waitSeconds()).isEqualTo(300L);
        verifyNoInteractions(mlPort);
    }

    @Test
    void TC_이미_지나간_버스_ML_호출() {
        props.setEnabled(true);
        mlProps.setEnabled(true);
        when(predictionSupport.support("100100360")).thenReturn(PredictionSupportService.Support.SUPPORTED);

        String routeId = "100100360";
        String stopId = "1111";

        when(stopRepo.findByStopId(stopId)).thenReturn(Optional.of(busStop(stopId)));
        when(routeRepo.findByRouteId(routeId)).thenReturn(Optional.of(busRoute(routeId, "360")));
        when(routeStopRepo.findByRouteIdAndStopId(routeId, stopId))
                .thenReturn(List.of(routeStop(routeId, stopId, 5)));

        // mkTm = KST 12:55:00, predictTime1 = 60s → 12:56 KST (user arrives 13:00 → 이미 지남)
        String mkTm = "20260606125500";
        BusArrivalInfo ai = arrivalInfo(routeId, mkTm, 60, 60); // both buses already passed
        when(busPort.getArrInfoByStop(stopId, routeId, 5)).thenReturn(arrOk(List.of(ai)));

        // position: sectOrd=3, targetSeq=5, isRunYn=1
        BusPositionInfo pos = new BusPositionInfo(
                "veh1", 60, 3, 500.0, 10000.0, "0", "s1",
                "20260606130001", "AB1234", 0, 120, "prevStop",
                null, null, "0", "0", 1000.0, "nextStop",
                2, "turnStop", 127.01, 37.51, "1"
        );
        when(busPort.getBusPosByRtid(routeId)).thenReturn(posOk(List.of(pos)));

        // ML returns 200s
        when(mlPort.predict(anyList())).thenAnswer(inv -> {
            List<MlFeatureVector> vectors = inv.getArgument(0);
            return vectors.stream()
                    .map(v -> new MlPrediction(v.requestId(), MlPredictionStatus.AVAILABLE, 200.0, "v1"))
                    .toList();
        });

        RouteSearchResult input = resultWith(List.of(busSubPath(stopId, routeId, "360")));
        RouteSearchResult result = enricher.enrich(input, NOW);

        TransferArrival ta = result.paths().get(0).subPaths().get(0).transferArrivals().get(0);
        assertThat(ta.source()).isEqualTo(TransferArrival.Source.MODEL);
        assertThat(ta.status()).isEqualTo(TransferArrival.Status.AVAILABLE);
        assertThat(ta.vehicleId()).isEqualTo("veh1");
    }

    @Test
    void TC_같은_stop_route_seq_dedup_1회만_호출() {
        props.setEnabled(true);
        String routeId = "100100360";
        String stopId = "1111";

        when(stopRepo.findByStopId(stopId)).thenReturn(Optional.of(busStop(stopId)));
        when(routeRepo.findByRouteId(routeId)).thenReturn(Optional.of(busRoute(routeId, "360")));
        when(routeStopRepo.findByRouteIdAndStopId(routeId, stopId))
                .thenReturn(List.of(routeStop(routeId, stopId, 5)));
        when(busPort.getArrInfoByStop(stopId, routeId, 5)).thenReturn(arrOk(List.of()));

        // 같은 (stopId, routeId, seq)를 가진 두 버스 subPath(서로 다른 path → 둘 다 wave0)
        PathInfo info = new PathInfo(3600, 2000, 0, 0, "출발", "도착", null);
        RouteSearchResult input = new RouteSearchResult(0, 2, 0, 0, 0, List.of(
                new PathResult(1, info, List.of(busSubPath(stopId, routeId, "360")), Collections.emptyList()),
                new PathResult(1, info, List.of(busSubPath(stopId, routeId, "360")), Collections.emptyList())));

        enricher.enrich(input, NOW);

        // 같은 (stopId, routeId, seq) → 1회만
        verify(busPort, times(1)).getArrInfoByStop(stopId, routeId, 5);
    }

    @Test
    void TC_ML_disabled_MODEL_UNAVAILABLE() {
        props.setEnabled(true);
        mlProps.setEnabled(false);
        when(predictionSupport.support("100100360")).thenReturn(PredictionSupportService.Support.SUPPORTED);

        String routeId = "100100360";
        String stopId = "1111";

        when(stopRepo.findByStopId(stopId)).thenReturn(Optional.of(busStop(stopId)));
        when(routeRepo.findByRouteId(routeId)).thenReturn(Optional.of(busRoute(routeId, "360")));
        when(routeStopRepo.findByRouteIdAndStopId(routeId, stopId))
                .thenReturn(List.of(routeStop(routeId, stopId, 5)));

        // 이미 지나간 버스 (no REALTIME)
        String mkTm = "20260606125500";
        BusArrivalInfo ai = arrivalInfo(routeId, mkTm, 60, 60); // both buses already passed
        when(busPort.getArrInfoByStop(stopId, routeId, 5)).thenReturn(arrOk(List.of(ai)));

        RouteSearchResult input = resultWith(List.of(busSubPath(stopId, routeId, "360")));
        RouteSearchResult result = enricher.enrich(input, NOW);

        TransferArrival ta = result.paths().get(0).subPaths().get(0).transferArrivals().get(0);
        assertThat(ta.status()).isEqualTo(TransferArrival.Status.MODEL_UNAVAILABLE);
        verifyNoInteractions(mlPort);
    }

    @Test
    void TC_wave1_직전_wave0_no_boardable_UPSTREAM_UNAVAILABLE() {
        props.setEnabled(true);
        String routeId = "100100360";
        String stopId = "1111";

        when(stopRepo.findByStopId(stopId)).thenReturn(Optional.of(busStop(stopId)));
        when(routeRepo.findByRouteId(routeId)).thenReturn(Optional.of(busRoute(routeId, "360")));
        when(routeStopRepo.findByRouteIdAndStopId(routeId, stopId))
                .thenReturn(List.of(routeStop(routeId, stopId, 5)));

        // wave0 bus: no REALTIME (all buses passed) + ML disabled → MODEL_UNAVAILABLE
        String mkTm = "20260606125500";
        BusArrivalInfo ai = arrivalInfo(routeId, mkTm, 60, 60); // both buses already passed
        when(busPort.getArrInfoByStop(stopId, routeId, 5)).thenReturn(arrOk(List.of(ai)));

        // Two bus subPaths in the same path (wave0, wave1)
        SubPathResult bus0 = busSubPath(stopId, routeId, "360");
        SubPathResult walk = walkSubPath(5);
        SubPathResult bus1 = busSubPath(stopId, routeId, "360");

        PathInfo info = new PathInfo(3600, 2000, 0, 0, "출발", "도착", null);
        RouteSearchResult input = new RouteSearchResult(0, 2, 0, 0, 0,
                List.of(new PathResult(1, info, List.of(bus0, walk, bus1), Collections.emptyList())));

        RouteSearchResult result = enricher.enrich(input, NOW);

        List<SubPathResult> subs = result.paths().get(0).subPaths();
        TransferArrival wave0result = subs.get(0).transferArrivals().get(0);
        TransferArrival wave1result = subs.get(2).transferArrivals().get(0);

        // wave0 - ML disabled, no REALTIME → MODEL_UNAVAILABLE (not boardable)
        assertThat(wave0result.status()).isNotEqualTo(TransferArrival.Status.UPSTREAM_UNAVAILABLE);
        // wave1 - upstream failed → UPSTREAM_UNAVAILABLE
        assertThat(wave1result.status()).isEqualTo(TransferArrival.Status.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void TC_walk_subPath_없는_경로_버스_도착정보_없음_NO_VEHICLE() {
        props.setEnabled(true);
        mlProps.setEnabled(true);
        when(predictionSupport.support("100100360")).thenReturn(PredictionSupportService.Support.SUPPORTED);

        String routeId = "100100360";
        String stopId = "1111";

        when(stopRepo.findByStopId(stopId)).thenReturn(Optional.of(busStop(stopId)));
        when(routeRepo.findByRouteId(routeId)).thenReturn(Optional.of(busRoute(routeId, "360")));
        when(routeStopRepo.findByRouteIdAndStopId(routeId, stopId))
                .thenReturn(List.of(routeStop(routeId, stopId, 5)));

        // all buses passed
        String mkTm = "20260606125500";
        BusArrivalInfo ai = arrivalInfo(routeId, mkTm, 60, 60); // both buses already passed
        when(busPort.getArrInfoByStop(stopId, routeId, 5)).thenReturn(arrOk(List.of(ai)));

        // position: no valid vehicles (sectOrd > targetSeq)
        BusPositionInfo pos = new BusPositionInfo(
                "veh1", 60, 10, 500.0, 10000.0, "0", "s1",
                "20260606130001", "AB1234", 0, 120, "prevStop",
                null, null, "0", "0", 1000.0, "nextStop",
                2, "turnStop", 127.01, 37.51, "1"
        );
        when(busPort.getBusPosByRtid(routeId)).thenReturn(posOk(List.of(pos)));

        RouteSearchResult input = resultWith(List.of(busSubPath(stopId, routeId, "360")));
        RouteSearchResult result = enricher.enrich(input, NOW);

        TransferArrival ta = result.paths().get(0).subPaths().get(0).transferArrivals().get(0);
        assertThat(ta.status()).isEqualTo(TransferArrival.Status.NO_VEHICLE);
    }

    @Test
    void TC_equality_차량_sectOrd_equals_targetSeq_포함() {
        props.setEnabled(true);
        mlProps.setEnabled(true);
        when(predictionSupport.support("100100360")).thenReturn(PredictionSupportService.Support.SUPPORTED);

        String routeId = "100100360";
        String stopId = "1111";

        when(stopRepo.findByStopId(stopId)).thenReturn(Optional.of(busStop(stopId)));
        when(routeRepo.findByRouteId(routeId)).thenReturn(Optional.of(busRoute(routeId, "360")));
        when(routeStopRepo.findByRouteIdAndStopId(routeId, stopId))
                .thenReturn(List.of(routeStop(routeId, stopId, 5)));

        // all buses passed
        String mkTm = "20260606125500";
        BusArrivalInfo ai = arrivalInfo(routeId, mkTm, 60, 60); // both buses already passed
        when(busPort.getArrInfoByStop(stopId, routeId, 5)).thenReturn(arrOk(List.of(ai)));

        // sectOrd == targetSeq = 5 (equality case: remaining_stop_count=0)
        BusPositionInfo pos = new BusPositionInfo(
                "veh1", 60, 5, 500.0, 1000.0, "0", "s1",
                "20260606130001", "AB1234", 0, 120, "prevStop",
                null, null, "0", "0", 1000.0, "nextStop",
                2, "turnStop", 127.01, 37.51, "1"
        );
        when(busPort.getBusPosByRtid(routeId)).thenReturn(posOk(List.of(pos)));

        when(mlPort.predict(anyList())).thenAnswer(inv -> {
            List<MlFeatureVector> vectors = inv.getArgument(0);
            return vectors.stream()
                    .map(v -> new MlPrediction(v.requestId(), MlPredictionStatus.AVAILABLE, 30.0, "v1"))
                    .toList();
        });

        RouteSearchResult input = resultWith(List.of(busSubPath(stopId, routeId, "360")));
        RouteSearchResult result = enricher.enrich(input, NOW);

        TransferArrival ta = result.paths().get(0).subPaths().get(0).transferArrivals().get(0);
        // sectOrd <= targetSeq (equality) → 포함되어야 함
        assertThat(ta.source()).isEqualTo(TransferArrival.Source.MODEL);
        assertThat(ta.status()).isEqualTo(TransferArrival.Status.AVAILABLE);
    }

    @Test
    void TC_per_search_외부호출_상한_초과분_조회_생략() {
        props.setEnabled(true);
        props.setMaxExternalCallsPerSearch(1); // 검색당 1콜만 허용

        when(stopRepo.findByStopId(any())).thenReturn(Optional.of(busStop("1111")));
        when(routeRepo.findByRouteId(any())).thenReturn(Optional.of(busRoute("100100360", "360")));
        when(routeStopRepo.findByRouteIdAndStopId(any(), any()))
                .thenReturn(List.of(routeStop("100100360", "1111", 5)));
        when(busPort.getArrInfoByStop(any(), any(), anyInt())).thenReturn(arrOk(List.of()));

        // 서로 다른 stopId 2개 버스 승차(각 다른 path → wave0 2건)
        SubPathResult bus1 = busSubPath("1111", "100100360", "360");
        SubPathResult bus2 = busSubPath("2222", "100100360", "360");
        PathInfo info = new PathInfo(3600, 1000, 0, 0, "출발", "도착", null);
        RouteSearchResult input = new RouteSearchResult(0, 2, 0, 0, 0, List.of(
                new PathResult(1, info, List.of(bus1), Collections.emptyList()),
                new PathResult(1, info, List.of(bus2), Collections.emptyList())));

        enricher.enrich(input, NOW);

        // 상한 1콜 → arrival 1회만(다른 (stop,route,seq) 1건은 생략)
        verify(busPort, times(1)).getArrInfoByStop(any(), any(), anyInt());
    }

    @Test
    void TC_deadline_초과_시_외부호출_없이_ERROR() {
        props.setEnabled(true);
        props.setDeadlineMs(100);

        // clock: enrich 진입 시 NOW(deadline=NOW+100ms), wave0 체크 시 NOW+2s(초과)
        java.time.Clock deadlineClock = mock(java.time.Clock.class);
        when(deadlineClock.instant()).thenReturn(NOW, NOW.plusSeconds(2));
        TransferArrivalEnricher deadlineEnricher = new TransferArrivalEnricher(
                props, mlProps, busPort, mlPort, resolver, featureBuilder, predictionSupport, deadlineClock, null);

        RouteSearchResult input = resultWith(List.of(busSubPath("1111", "route1", "360")));
        RouteSearchResult result = deadlineEnricher.enrich(input, NOW);

        TransferArrival ta = result.paths().get(0).subPaths().get(0).transferArrivals().get(0);
        assertThat(ta.status()).isEqualTo(TransferArrival.Status.ERROR);
        // deadline 초과 → 외부 호출 전부 생략
        verifyNoInteractions(busPort, mlPort);
    }

    @Test
    void TC_검색_context의_잔여예산을_버스_호출까지_전파() {
        props.setEnabled(true);
        when(stopRepo.findByStopId(any())).thenReturn(Optional.of(busStop("1111")));
        when(routeRepo.findByRouteId(any())).thenReturn(Optional.of(busRoute("100100360", "360")));
        when(routeStopRepo.findByRouteIdAndStopId(any(), any()))
                .thenReturn(List.of(routeStop("100100360", "1111", 5)));
        when(busPort.getArrInfoByStop(eq("1111"), eq("100100360"), eq(5), anyLong()))
                .thenReturn(arrOk(List.of()));

        RouteSearchResult input = resultWith(List.of(busSubPath("1111", "100100360", "360")));
        enricher.enrich(input, NOW, RouteSearchContext.start(500));

        verify(busPort).getArrInfoByStop(eq("1111"), eq("100100360"), eq(5),
                longThat(ms -> ms > 0 && ms <= 500));
        verify(busPort, never()).getArrInfoByStop(any(), any(), anyInt());
    }

    // ── search-regional-bus-wait PR-2: 경기·TAGO 실시간, ARRIVAL_ONLY ─────────────────

    private static final String GG_STOP = "202000208";
    private static final String GG_ROUTE = "234000026";

    @Test
    @org.junit.jupiter.api.DisplayName("환승 도착 계산: 탄다고 본 lane 의 탑승시간을 쓴다 — 구간 sectionTime 은 대표 노선 값이다(bus-lane-selection PR-1)")
    void rideSecondsOfTheBasisLane() {
        // 구간 8분(대표 8100), 묶인 M4102 는 같은 정류장 사이를 14분에 간다(운영 실측 최대 차 9.3분)
        SubPathResult bus = busSubPathLanes("111", List.of(
                new LaneResult("8100", "8100", null, 1, null, "100100001", null, 480),
                new LaneResult("M4102", "M4102", null, 1, null, "100100002", null, 840),
                new LaneResult("9000", "9000", null, 1, null, "100100003", null)));

        assertThat(TransferArrivalEnricher.rideSeconds(bus, 0)).isEqualTo(480);
        assertThat(TransferArrivalEnricher.rideSeconds(bus, 1)).isEqualTo(840);
        // 그 lane 값을 모르거나(옛 스냅샷·ODsay) lane 을 모르면 구간 값
        assertThat(TransferArrivalEnricher.rideSeconds(bus, 2)).isEqualTo(bus.sectionTime() * 60L);
        assertThat(TransferArrivalEnricher.rideSeconds(bus, null)).isEqualTo(bus.sectionTime() * 60L);
        assertThat(TransferArrivalEnricher.rideSeconds(bus, 9)).isEqualTo(bus.sectionTime() * 60L);
    }

    private static SearchRegionalArrivalService.Pending done(SearchRegionalArrivalService.Outcome o, Integer... secondsAhead) {
        return new SearchRegionalArrivalService.Pending(SearchRegionalArrivalService.Provider.GG,
                java.util.concurrent.CompletableFuture.completedFuture(
                        new SearchRegionalArrivalService.Result(o, NOW, List.of(secondsAhead))));
    }

    private RouteSearchResult path(SubPathResult... sps) {
        PathInfo info = new PathInfo(3600, 2000, 0, 0, "출발", "도착", null);
        return new RouteSearchResult(0, 2, 0, 0, 0, List.of(new PathResult(1, info, List.of(sps), Collections.emptyList())));
    }

    @Test
    void TC_경기_버스는_지역_실시간으로_대기를_계산한다() {
        props.setEnabled(true);
        when(regional.enabled()).thenReturn(true);
        when(regional.start(SearchRegionalArrivalService.Provider.GG, GG_ROUTE, GG_STOP))
                .thenReturn(done(SearchRegionalArrivalService.Outcome.OK, 300, 900));

        TransferArrival ta = enricher.enrich(resultWith(List.of(busSubPath(GG_STOP, GG_ROUTE, "720-2"))), NOW)
                .paths().get(0).subPaths().get(0).transferArrivals().get(0);

        assertThat(ta.status()).isEqualTo(TransferArrival.Status.AVAILABLE);
        assertThat(ta.source()).isEqualTo(TransferArrival.Source.REALTIME);
        assertThat(ta.waitSeconds()).isEqualTo(300L);
        assertThat(ta.routeId()).isEqualTo(GG_ROUTE);
        verifyNoInteractions(busPort);
    }

    @Test
    void TC_경기_실시간_다음_서울_버스는_환승_대기까지_이어서_계산한다() {
        props.setEnabled(true);
        when(regional.enabled()).thenReturn(true);
        when(regional.start(SearchRegionalArrivalService.Provider.GG, GG_ROUTE, GG_STOP))
                .thenReturn(done(SearchRegionalArrivalService.Outcome.OK, 300));
        String seoulRoute = "100100360";
        lenient().when(stopRepo.findByStopId(GG_STOP)).thenReturn(Optional.empty());
        lenient().when(routeRepo.findByRouteId(GG_ROUTE)).thenReturn(Optional.empty());
        when(stopRepo.findByStopId("1111")).thenReturn(Optional.of(busStop("1111")));
        when(routeRepo.findByRouteId(seoulRoute)).thenReturn(Optional.of(busRoute(seoulRoute, "360")));
        when(routeStopRepo.findByRouteIdAndStopId(seoulRoute, "1111")).thenReturn(List.of(routeStop(seoulRoute, "1111", 5)));
        // 도착 = 경기 탑승 NOW+300 + 승차 10분 + 도보 5분 = NOW+1200. 서울 버스 NOW+1500 → 대기 300
        when(busPort.getArrInfoByStop(eq("1111"), eq(seoulRoute), eq(5), anyLong()))
                .thenReturn(arrOk(List.of(arrivalInfo(seoulRoute, "20260606130000", 1500, -1))));

        List<SubPathResult> subs = enricher.enrich(path(busSubPath(GG_STOP, GG_ROUTE, "720-2"), walkSubPath(5),
                busSubPath("1111", seoulRoute, "360")), NOW, RouteSearchContext.start(5000)).paths().get(0).subPaths();

        TransferArrival seoul = subs.get(2).transferArrivals().get(0);
        assertThat(seoul.source()).isEqualTo(TransferArrival.Source.REALTIME);
        assertThat(seoul.userArrivalAt()).isEqualTo(NOW.plusSeconds(1200));
        assertThat(seoul.waitSeconds()).isEqualTo(300L);
    }

    @Test
    void TC_지역_조회_한도와_실패는_LIMITED_ERROR() {
        props.setEnabled(true);
        when(regional.enabled()).thenReturn(true);
        when(regional.start(SearchRegionalArrivalService.Provider.GG, GG_ROUTE, GG_STOP))
                .thenReturn(done(SearchRegionalArrivalService.Outcome.LIMITED),
                        done(SearchRegionalArrivalService.Outcome.TIMEOUT));

        TransferArrival limited = enricher.enrich(resultWith(List.of(busSubPath(GG_STOP, GG_ROUTE, "720-2"))), NOW)
                .paths().get(0).subPaths().get(0).transferArrivals().get(0);
        TransferArrival timeout = enricher.enrich(resultWith(List.of(busSubPath(GG_STOP, GG_ROUTE, "720-2"))), NOW)
                .paths().get(0).subPaths().get(0).transferArrivals().get(0);

        assertThat(limited.status()).isEqualTo(TransferArrival.Status.LIMITED);
        assertThat(timeout.status()).isEqualTo(TransferArrival.Status.ERROR);
    }

    @Test
    void TC_지역_조회가_꺼지면_경기_버스는_지금처럼_STOP_MAPPING_FAILED() {
        props.setEnabled(true);

        TransferArrival ta = enricher.enrich(resultWith(List.of(busSubPath(GG_STOP, GG_ROUTE, "720-2"))), NOW)
                .paths().get(0).subPaths().get(0).transferArrivals().get(0);

        assertThat(ta.status()).isEqualTo(TransferArrival.Status.STOP_MAPPING_FAILED);
        verify(regional, never()).start(any(), any(), any());
    }

    @Test
    void TC_앞_버스_결과가_없으면_뒤_버스는_도착만_싣고_대기는_계산하지_않는다() {
        props.setEnabled(true);
        props.setArrivalOnlyEnabled(true);
        String seoulRoute = "100100360";
        lenient().when(stopRepo.findByStopId(GG_STOP)).thenReturn(Optional.empty());
        lenient().when(routeRepo.findByRouteId(GG_ROUTE)).thenReturn(Optional.empty());
        when(stopRepo.findByStopId("1111")).thenReturn(Optional.of(busStop("1111")));
        when(routeRepo.findByRouteId(seoulRoute)).thenReturn(Optional.of(busRoute(seoulRoute, "360")));
        when(routeStopRepo.findByRouteIdAndStopId(seoulRoute, "1111")).thenReturn(List.of(routeStop(seoulRoute, "1111", 5)));
        when(busPort.getArrInfoByStop(eq("1111"), eq(seoulRoute), eq(5), anyLong()))
                .thenReturn(arrOk(List.of(arrivalInfo(seoulRoute, "20260606130000", 420, -1))));

        // 앞 버스 = 경기(지역 조회 꺼짐) → 결과 없음 → 뒤 서울 버스는 도착만
        List<SubPathResult> subs = enricher.enrich(path(busSubPath(GG_STOP, GG_ROUTE, "720-2"), walkSubPath(5),
                busSubPath("1111", seoulRoute, "360")), NOW, RouteSearchContext.start(5000)).paths().get(0).subPaths();

        TransferArrival back = subs.get(2).transferArrivals().get(0);
        assertThat(back.status()).isEqualTo(TransferArrival.Status.ARRIVAL_ONLY);
        assertThat(back.source()).isEqualTo(TransferArrival.Source.REALTIME);
        assertThat(back.predictedArrivalAt()).isEqualTo(NOW.plusSeconds(420));
        assertThat(back.waitSeconds()).isNull();
        assertThat(back.userArrivalAt()).isNull();
    }

    @Test
    void TC_도착만_꺼지면_뒤_버스는_조회하지_않고_UPSTREAM_UNAVAILABLE() {
        props.setEnabled(true);
        String seoulRoute = "100100360";

        List<SubPathResult> subs = enricher.enrich(path(busSubPath(GG_STOP, GG_ROUTE, "720-2"), walkSubPath(5),
                busSubPath("1111", seoulRoute, "360")), NOW, RouteSearchContext.start(5000)).paths().get(0).subPaths();

        assertThat(subs.get(2).transferArrivals().get(0).status()).isEqualTo(TransferArrival.Status.UPSTREAM_UNAVAILABLE);
        verifyNoInteractions(busPort);
    }

    @Test
    void TC_앞_경기_버스가_한도로_실패하면_뒤_경기_버스는_지역_도착만() {
        props.setEnabled(true);
        props.setArrivalOnlyEnabled(true);
        when(regional.enabled()).thenReturn(true);
        when(regional.start(SearchRegionalArrivalService.Provider.GG, GG_ROUTE, GG_STOP))
                .thenReturn(done(SearchRegionalArrivalService.Outcome.LIMITED));
        when(regional.start(SearchRegionalArrivalService.Provider.GG, "234000099", "202000300"))
                .thenReturn(done(SearchRegionalArrivalService.Outcome.OK, 240, 840));

        List<SubPathResult> subs = enricher.enrich(path(busSubPath(GG_STOP, GG_ROUTE, "720-2"), walkSubPath(5),
                busSubPath("202000300", "234000099", "7002")), NOW, RouteSearchContext.start(5000)).paths().get(0).subPaths();

        TransferArrival back = subs.get(2).transferArrivals().get(0);
        assertThat(back.status()).isEqualTo(TransferArrival.Status.ARRIVAL_ONLY);
        assertThat(back.routeId()).isEqualTo("234000099");
        assertThat(back.predictedArrivalAt()).isEqualTo(NOW.plusSeconds(240));
        assertThat(back.waitSeconds()).isNull();
    }

    @Test
    void TC_상한이_없으면_결과의_지역_버스를_모두_묻는다() {
        props.setEnabled(true);
        when(regional.enabled()).thenReturn(true);
        when(regional.start(eq(SearchRegionalArrivalService.Provider.GG), anyString(), anyString()))
                .thenReturn(done(SearchRegionalArrivalService.Outcome.OK, 300));
        PathInfo info = new PathInfo(3600, 2000, 0, 0, "출발", "도착", null);
        List<PathResult> paths = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            paths.add(new PathResult(1, info, List.of(busSubPath("20200" + (1000 + i), "2340" + (10000 + i), "b" + i)),
                    Collections.emptyList()));
        }

        RouteSearchResult out = enricher.enrich(new RouteSearchResult(0, 10, 0, 0, 0, paths), NOW);

        verify(regional, times(10)).start(eq(SearchRegionalArrivalService.Provider.GG), anyString(), anyString());
        assertThat(out.paths()).allSatisfy(p -> assertThat(p.subPaths().get(0).transferArrivals().get(0).status())
                .isEqualTo(TransferArrival.Status.AVAILABLE));
    }

    @Test
    void TC_상한을_주면_그만큼만_묻는다() {
        props.setEnabled(true);
        props.setRegionalMaxCallsPerSearch(2);
        when(regional.enabled()).thenReturn(true);
        when(regional.start(eq(SearchRegionalArrivalService.Provider.GG), anyString(), anyString()))
                .thenReturn(done(SearchRegionalArrivalService.Outcome.OK, 300));
        PathInfo info = new PathInfo(3600, 2000, 0, 0, "출발", "도착", null);
        List<PathResult> paths = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            paths.add(new PathResult(1, info, List.of(busSubPath("20200" + (1000 + i), "2340" + (10000 + i), "b" + i)),
                    Collections.emptyList()));
        }

        enricher.enrich(new RouteSearchResult(0, 4, 0, 0, 0, paths), NOW);

        verify(regional, times(2)).start(eq(SearchRegionalArrivalService.Provider.GG), anyString(), anyString());
    }
}
