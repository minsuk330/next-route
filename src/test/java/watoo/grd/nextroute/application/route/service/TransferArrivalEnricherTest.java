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
        // 운행이 끝난 750B 는 구간에서 빠지고, 남은 360 이 첫 노선이 된다 — 순번도 같이 당겨진다
        SubPathResult leg = out.paths().get(0).subPaths().get(0);
        assertThat(leg.lanes()).extracting(LaneResult::busNo).containsExactly("360");
        assertThat(leg.transferArrivals()).hasSize(1);
        assertThat(leg.transferArrivals().get(0).laneIndex()).isZero();
        assertThat(leg.transferArrivals().get(0).routeId()).isEqualTo("100100360");
        assertThat(leg.transferArrivals().get(0).status()).isEqualTo(TransferArrival.Status.AVAILABLE);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("운행종료 노선 빼기: 가운데 노선이 빠져도 남은 노선의 순번, 다음 구간의 기준 순번, 앞 구간의 환승 대기 순번이 맞는다")
    void endedLaneIsDroppedAndEveryLaneIndexIsRemapped() {
        props.setEnabled(true);
        lenient().when(predictionSupport.support(any())).thenReturn(PredictionSupportService.Support.UNSUPPORTED);
        // 첫 구간(정류장 1111): 750B 운행종료, 360 은 13:04:50 도착
        stubRoute("100100750", "750B", endedInfo("100100750", "운행종료", "운행종료", "20260606043500"));
        stubRoute("100100360", "360", arrivalInfo("100100360", "2026-06-06 12:59:50.0", 300, 1200));
        SubPathResult first = busSubPathLanes("1111", List.of(
                new LaneResult("750B", "750B", null, 1, null, "100100750", null, 840),
                new LaneResult("360", "360", null, 1, null, "100100360", null, 840)));
        // 둘째 구간(정류장 2222): 998 운행종료, 999 는 13:19:50 과 13:29:50 에 온다
        when(stopRepo.findByStopId("2222")).thenReturn(Optional.of(busStop("2222")));
        for (String[] r : new String[][]{{"100100998", "998"}, {"100100999", "999"}}) {
            when(routeRepo.findByRouteId(r[0])).thenReturn(Optional.of(busRoute(r[0], r[1])));
            when(routeStopRepo.findByRouteIdAndStopId(r[0], "2222")).thenReturn(List.of(routeStop(r[0], "2222", 7)));
        }
        BusArrivalInfo ended = endedInfo("100100998", "운행종료", "운행종료", "20260606043500");
        BusArrivalInfo alive = arrivalInfo("100100999", "2026-06-06 12:59:50.0", 1200, 1800);
        lenient().when(busPort.getArrInfoByStop(eq("2222"), eq("100100998"), eq(7), anyLong())).thenReturn(arrOk(List.of(ended)));
        lenient().when(busPort.getArrInfoByStop(eq("2222"), eq("100100999"), eq(7), anyLong())).thenReturn(arrOk(List.of(alive)));
        SubPathResult second = busSubPathLanes("2222", List.of(
                new LaneResult("998", "998", null, 1, null, "100100998", null, 600),
                new LaneResult("999", "999", null, 1, null, "100100999", null, 600)));

        RouteSearchResult out = enricher.enrich(resultWith(List.of(first, second)), NOW, RouteSearchContext.start(5000));

        SubPathResult a = out.paths().get(0).subPaths().get(0);
        SubPathResult b = out.paths().get(0).subPaths().get(1);
        assertThat(a.lanes()).extracting(LaneResult::busNo).containsExactly("360");
        assertThat(b.lanes()).extracting(LaneResult::busNo).containsExactly("999");
        TransferArrival via360 = a.transferArrivals().get(0);
        assertThat(via360.laneIndex()).isZero();
        // 앞 구간의 환승 대기는 둘째 구간의 남은 노선(999, 이제 0번)만 가리킨다
        assertThat(via360.onwardWaits()).hasSize(1);
        assertThat(via360.onwardWaits().get(0).laneIndex()).isZero();
        assertThat(via360.onwardWaits().get(0).routeId()).isEqualTo("100100999");
        assertThat(via360.onwardWaits().get(0).waitSeconds()).isEqualTo(60);
        TransferArrival via999 = b.transferArrivals().get(0);
        assertThat(b.transferArrivals()).hasSize(1);
        assertThat(via999.laneIndex()).isZero();
        assertThat(via999.routeId()).isEqualTo("100100999");
        // 둘째 구간이 "첫 구간의 어느 버스를 탔다고 봤나"도 당겨진 순번(360 = 0번)이다
        assertThat(via999.basisLaneIndex()).isZero();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("운행종료 노선 빼기: 실시간을 못 읽은 노선은 근거가 없어 남긴다")
    void laneWithoutRealtimeIsKept() {
        props.setEnabled(true);
        lenient().when(predictionSupport.support(any())).thenReturn(PredictionSupportService.Support.UNSUPPORTED);
        stubRoute("100100750", "750B", endedInfo("100100750", "운행종료", "운행종료", "20260606043500"));
        stubRoute("100100360", "360", arrivalInfo("100100360", "2026-06-06 12:59:50.0", 300));
        lenient().when(routeRepo.findByRouteId("100199999")).thenReturn(Optional.empty());
        lenient().when(routeRepo.findByRouteNameIn(any())).thenReturn(List.of());
        SubPathResult lanes = busSubPathLanes("1111", List.of(
                new LaneResult("360", "360", null, 1, null, "100100360", null),
                new LaneResult("750B", "750B", null, 1, null, "100100750", null),
                new LaneResult("unknown", "unknown", null, 1, null, "100199999", null)));

        RouteSearchResult out = enricher.enrich(resultWith(List.of(lanes)), NOW, RouteSearchContext.start(5000));

        SubPathResult leg = out.paths().get(0).subPaths().get(0);
        assertThat(leg.lanes()).extracting(LaneResult::busNo).containsExactly("360", "unknown");
        assertThat(leg.transferArrivals()).extracting(TransferArrival::laneIndex).containsExactly(0, 1);
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
    @org.junit.jupiter.api.DisplayName("버스 → 버스 환승: 먼저 오는 lane 을 탄다고 보고 그 lane 의 탑승시간으로 다음 정류장 도착을 잰다")
    void transferArrivalUsesTheRideTimeOfTheEarliestLane() {
        props.setEnabled(true);
        lenient().when(predictionSupport.support(any())).thenReturn(PredictionSupportService.Support.UNSUPPORTED);
        // 첫 구간(정류장 1111): 750B 는 10분 뒤, 360 은 5분 뒤 도착. 구간 sectionTime 10분(대표 750B 600s), 360 은 840s 걸린다
        stubRoute("100100750", "750B", arrivalInfo("100100750", "2026-06-06 12:59:50.0", 600, 1200));
        stubRoute("100100360", "360", arrivalInfo("100100360", "2026-06-06 12:59:50.0", 300, 1200));
        SubPathResult first = busSubPathLanes("1111", List.of(
                new LaneResult("750B", "750B", null, 1, null, "100100750", null, 600),
                new LaneResult("360", "360", null, 1, null, "100100360", null, 840)));
        // 둘째 구간(정류장 2222, 노선 100100999)
        when(stopRepo.findByStopId("2222")).thenReturn(Optional.of(busStop("2222")));
        when(routeRepo.findByRouteId("100100999")).thenReturn(Optional.of(busRoute("100100999", "999")));
        when(routeStopRepo.findByRouteIdAndStopId("100100999", "2222")).thenReturn(List.of(routeStop("100100999", "2222", 7)));
        BusArrivalInfo next = arrivalInfo("100100999", "2026-06-06 12:59:50.0", 1800, 2400);
        lenient().when(busPort.getArrInfoByStop(eq("2222"), eq("100100999"), eq(7), anyLong())).thenReturn(arrOk(List.of(next)));
        lenient().when(busPort.getArrInfoByStop("2222", "100100999", 7)).thenReturn(arrOk(List.of(next)));
        SubPathResult second = busSubPath("2222", "100100999", "999");

        RouteSearchResult out = enricher.enrich(resultWith(List.of(first, second)), NOW, RouteSearchContext.start(5000));

        TransferArrival ta = out.paths().get(0).subPaths().get(1).transferArrivals().get(0);
        // 360 탑승 13:04:50 KST + 840s = 13:18:50 KST. 대표(750B) 탑승시간 600s 를 쓰면 13:14:50 이 된다
        assertThat(ta.userArrivalAt()).isEqualTo(Instant.parse("2026-06-06T04:18:50Z"));

        // 앞 구간의 lane 마다 "그 버스를 타면 다음 정류장에서 얼마나 기다리나"가 붙는다(999 는 13:29:50, 13:39:50 도착)
        List<TransferArrival> firstLanes = out.paths().get(0).subPaths().get(0).transferArrivals();
        // 750B: 13:09:50 탑승 + 600s = 13:19:50 도착 → 999 13:29:50 차, 대기 600s
        watoo.grd.nextroute.application.route.dto.OnwardWait via750 = firstLanes.get(0).onwardWaits().get(0);
        assertThat(via750.userArrivalAt()).isEqualTo(Instant.parse("2026-06-06T04:19:50Z"));
        assertThat(via750.predictedArrivalAt()).isEqualTo(Instant.parse("2026-06-06T04:29:50Z"));
        assertThat(via750.waitSeconds()).isEqualTo(600);
        // 360: 13:04:50 탑승 + 840s = 13:18:50 도착 → 같은 차, 대기 660s
        watoo.grd.nextroute.application.route.dto.OnwardWait via360 = firstLanes.get(1).onwardWaits().get(0);
        assertThat(via360.laneIndex()).isZero();
        assertThat(via360.routeId()).isEqualTo("100100999");
        assertThat(via360.waitSeconds()).isEqualTo(660);
        // 마지막 버스 구간에는 다음 구간이 없다
        assertThat(ta.onwardWaits()).isNull();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("다음 환승 대기: 탈 수 없는 lane 에는 붙지 않고, 닿는 시각이 아는 두 대보다 뒤면 대기는 모름(null)이다")
    void onwardWaitsUnknownOutsideTheKnownBuses() {
        props.setEnabled(true);
        lenient().when(predictionSupport.support(any())).thenReturn(PredictionSupportService.Support.UNSUPPORTED);
        // 첫 구간: 360 은 5분 뒤 도착(탈 수 있음), 모르는 노선(100199999)은 해석 실패(탈 수 없음)
        stubRoute("100100360", "360", arrivalInfo("100100360", "2026-06-06 12:59:50.0", 300, 1200));
        lenient().when(routeRepo.findByRouteId("100199999")).thenReturn(Optional.empty());
        lenient().when(routeRepo.findByRouteNameIn(any())).thenReturn(List.of());
        SubPathResult first = busSubPathLanes("1111", List.of(
                new LaneResult("360", "360", null, 1, null, "100100360", null, 840),
                new LaneResult("unknown", "unknown", null, 1, null, "100199999", null, 840)));
        // 둘째 구간: 999 는 13:04:50, 13:09:50 두 대만 안다 — 360 을 타고 닿는 13:18:50 보다 앞이다
        when(stopRepo.findByStopId("2222")).thenReturn(Optional.of(busStop("2222")));
        when(routeRepo.findByRouteId("100100999")).thenReturn(Optional.of(busRoute("100100999", "999")));
        when(routeStopRepo.findByRouteIdAndStopId("100100999", "2222")).thenReturn(List.of(routeStop("100100999", "2222", 7)));
        BusArrivalInfo next = arrivalInfo("100100999", "2026-06-06 12:59:50.0", 300, 600);
        lenient().when(busPort.getArrInfoByStop(eq("2222"), eq("100100999"), eq(7), anyLong())).thenReturn(arrOk(List.of(next)));
        lenient().when(busPort.getArrInfoByStop("2222", "100100999", 7)).thenReturn(arrOk(List.of(next)));

        RouteSearchResult out = enricher.enrich(resultWith(List.of(first, busSubPath("2222", "100100999", "999"))),
                NOW, RouteSearchContext.start(5000));

        List<TransferArrival> firstLanes = out.paths().get(0).subPaths().get(0).transferArrivals();
        watoo.grd.nextroute.application.route.dto.OnwardWait via360 = firstLanes.get(0).onwardWaits().get(0);
        assertThat(via360.userArrivalAt()).isEqualTo(Instant.parse("2026-06-06T04:18:50Z"));
        assertThat(via360.predictedArrivalAt()).isNull();
        assertThat(via360.waitSeconds()).isNull();
        assertThat(firstLanes.get(1).predictedArrivalAt()).isNull();
        assertThat(firstLanes.get(1).onwardWaits()).isNull();
    }

    /** 둘째 구간(정류장 2222, 노선 999, 순번 7)과 그 노선의 운행 차량 하나 — 모델은 그 차가 mlSeconds 뒤 닿는다고 본다. */
    private void stubSecondLegWithModel(BusArrivalInfo realtime, double mlSeconds) {
        mlProps.setEnabled(true);
        lenient().when(predictionSupport.support(any())).thenReturn(PredictionSupportService.Support.SUPPORTED);
        when(stopRepo.findByStopId("2222")).thenReturn(Optional.of(busStop("2222")));
        when(routeRepo.findByRouteId("100100999")).thenReturn(Optional.of(busRoute("100100999", "999")));
        when(routeStopRepo.findByRouteIdAndStopId("100100999", "2222")).thenReturn(List.of(routeStop("100100999", "2222", 7)));
        lenient().when(busPort.getArrInfoByStop("2222", "100100999", 7)).thenReturn(arrOk(List.of(realtime)));
        // 13:00:01 에 5번째 구간을 달리는 차(목표 순번 7 앞)
        BusPositionInfo pos = new BusPositionInfo(
                "veh999", 60, 3, 500.0, 10000.0, "0", "s1",
                "20260606130001", "AB1234", 0, 120, "prevStop",
                null, null, "0", "0", 1000.0, "nextStop",
                2, "turnStop", 127.01, 37.51, "1");
        lenient().when(busPort.getBusPosByRtid("100100999")).thenReturn(posOk(List.of(pos)));
        lenient().when(mlPort.predict(anyList())).thenAnswer(inv -> {
            List<MlFeatureVector> vectors = inv.getArgument(0);
            return vectors.stream()
                    .map(v -> new MlPrediction(v.requestId(), MlPredictionStatus.AVAILABLE, mlSeconds, "v1"))
                    .toList();
        });
    }

    @Test
    @org.junit.jupiter.api.DisplayName("다음 환승 대기: 닿는 시각이 실시간 두 대보다 뒤면 예측 모델로 채운다(출처 MODEL)")
    void onwardWaitsFallBackToModel() {
        props.setEnabled(true);
        // 첫 구간: 360 은 13:04:50 도착, 탑승 840초 → 둘째 정류장에 13:18:50
        stubRoute("100100360", "360", arrivalInfo("100100360", "2026-06-06 12:59:50.0", 300, 1200));
        SubPathResult first = busSubPathLanes("1111", List.of(
                new LaneResult("360", "360", null, 1, null, "100100360", null, 840)));
        // 둘째 구간: 실시간은 13:04:50, 13:09:50 두 대뿐. 모델은 운행 중인 차가 13:00:01 + 1500초 = 13:25:01 에 온다고 본다
        stubSecondLegWithModel(arrivalInfo("100100999", "2026-06-06 12:59:50.0", 300, 600), 1500.0);

        RouteSearchResult out = enricher.enrich(resultWith(List.of(first, busSubPath("2222", "100100999", "999"))), NOW);

        watoo.grd.nextroute.application.route.dto.OnwardWait via360 =
                out.paths().get(0).subPaths().get(0).transferArrivals().get(0).onwardWaits().get(0);
        assertThat(via360.userArrivalAt()).isEqualTo(Instant.parse("2026-06-06T04:18:50Z"));
        assertThat(via360.predictedArrivalAt()).isEqualTo(Instant.parse("2026-06-06T04:25:01Z"));
        assertThat(via360.waitSeconds()).isEqualTo(371);
        assertThat(via360.source()).isEqualTo(TransferArrival.Source.MODEL);
        // 둘째 구간 자신의 결과(대표 기준)와 같은 차다
        TransferArrival second = out.paths().get(0).subPaths().get(1).transferArrivals().get(0);
        assertThat(second.source()).isEqualTo(TransferArrival.Source.MODEL);
        assertThat(second.predictedArrivalAt()).isEqualTo(via360.predictedArrivalAt());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("다음 환승 대기: 먼저 오는 버스는 실시간으로, 늦게 닿는 묶인 버스만 모델로 — 대표가 실시간이어도 모델을 부른다")
    void onwardWaitsMixRealtimeAndModelPerLane() {
        props.setEnabled(true);
        // 첫 구간: 361 은 13:01:50 도착·탑승 300초 → 13:06:50, 360 은 13:04:50 도착·탑승 1500초 → 13:29:50
        stubRoute("100100360", "360", arrivalInfo("100100360", "2026-06-06 12:59:50.0", 300, 1200));
        stubRoute("100100361", "361", arrivalInfo("100100361", "2026-06-06 12:59:50.0", 120, 1200));
        SubPathResult first = busSubPathLanes("1111", List.of(
                new LaneResult("360", "360", null, 1, null, "100100360", null, 1500),
                new LaneResult("361", "361", null, 1, null, "100100361", null, 300)));
        // 둘째 구간: 실시간 13:19:50, 13:21:30. 모델은 13:00:01 + 2000초 = 13:33:21
        stubSecondLegWithModel(arrivalInfo("100100999", "2026-06-06 12:59:50.0", 1200, 1300), 2000.0);

        RouteSearchResult out = enricher.enrich(resultWith(List.of(first, busSubPath("2222", "100100999", "999"))), NOW);

        List<TransferArrival> firstLanes = out.paths().get(0).subPaths().get(0).transferArrivals();
        watoo.grd.nextroute.application.route.dto.OnwardWait via361 = firstLanes.get(1).onwardWaits().get(0);
        assertThat(via361.source()).isEqualTo(TransferArrival.Source.REALTIME);
        assertThat(via361.predictedArrivalAt()).isEqualTo(Instant.parse("2026-06-06T04:19:50Z"));
        assertThat(via361.waitSeconds()).isEqualTo(780);
        watoo.grd.nextroute.application.route.dto.OnwardWait via360 = firstLanes.get(0).onwardWaits().get(0);
        assertThat(via360.userArrivalAt()).isEqualTo(Instant.parse("2026-06-06T04:29:50Z"));
        assertThat(via360.source()).isEqualTo(TransferArrival.Source.MODEL);
        assertThat(via360.predictedArrivalAt()).isEqualTo(Instant.parse("2026-06-06T04:33:21Z"));
        assertThat(via360.waitSeconds()).isEqualTo(211);
        // 둘째 구간의 대표 결과는 실시간 그대로다
        assertThat(out.paths().get(0).subPaths().get(1).transferArrivals().get(0).source())
                .isEqualTo(TransferArrival.Source.REALTIME);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("다음 환승 대기: 모델이 꺼져 있으면 실시간 밖은 모름으로 남고 모델을 부르지 않는다")
    void onwardWaitsStayUnknownWhenModelDisabled() {
        props.setEnabled(true);
        stubRoute("100100360", "360", arrivalInfo("100100360", "2026-06-06 12:59:50.0", 300, 1200));
        SubPathResult first = busSubPathLanes("1111", List.of(
                new LaneResult("360", "360", null, 1, null, "100100360", null, 840)));
        stubSecondLegWithModel(arrivalInfo("100100999", "2026-06-06 12:59:50.0", 300, 600), 1500.0);
        mlProps.setEnabled(false);

        RouteSearchResult out = enricher.enrich(resultWith(List.of(first, busSubPath("2222", "100100999", "999"))), NOW);

        watoo.grd.nextroute.application.route.dto.OnwardWait via360 =
                out.paths().get(0).subPaths().get(0).transferArrivals().get(0).onwardWaits().get(0);
        assertThat(via360.waitSeconds()).isNull();
        assertThat(via360.source()).isNull();
        verifyNoInteractions(mlPort);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("검색 스냅샷 호환: onwardWaits 가 없는 옛 JSON 은 null 로 읽히고, 새 JSON 은 그대로 돌아온다")
    void transferArrivalJsonRoundTrip() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        TransferArrival old = om.readValue("{\"routeId\":\"100100360\",\"laneIndex\":0,\"source\":\"REALTIME\","
                + "\"status\":\"AVAILABLE\",\"calculatedAt\":\"2026-06-06T04:00:00Z\",\"userArrivalAt\":\"2026-06-06T04:00:00Z\","
                + "\"predictedArrivalAt\":\"2026-06-06T04:04:50Z\",\"waitSeconds\":290,\"vehicleId\":null,\"modelVersion\":null,"
                + "\"basisLaneIndex\":null,\"conditional\":false}", TransferArrival.class);
        assertThat(old.onwardWaits()).isNull();
        assertThat(old.waitSeconds()).isEqualTo(290);

        TransferArrival withWaits = old.withOnwardWaits(List.of(new watoo.grd.nextroute.application.route.dto.OnwardWait(
                0, "100100999", Instant.parse("2026-06-06T04:18:50Z"), Instant.parse("2026-06-06T04:29:50Z"), 660L)));
        TransferArrival back = om.readValue(om.writeValueAsString(withWaits), TransferArrival.class);
        assertThat(back).isEqualTo(withWaits);
        assertThat(back.onwardWaits().get(0).waitSeconds()).isEqualTo(660);
    }

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
