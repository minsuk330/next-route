package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.config.BusCollectorProperties;
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

    TransferArrivalProperties props = new TransferArrivalProperties();
    MlPredictorProperties mlProps = new MlPredictorProperties();
    BusCollectorProperties collectorProps = new BusCollectorProperties();
    TransferStopResolver resolver;
    MlFeatureVectorBuilder featureBuilder;
    TransferArrivalEnricher enricher;

    static final Instant NOW = Instant.parse("2026-06-06T04:00:00Z"); // KST 13:00

    // deadline 미초과(NOW 고정 clock — clock.instant()는 항상 NOW, deadline=NOW+1500ms 안 넘음)
    java.time.Clock clock = java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        resolver = new TransferStopResolver(stopRepo, routeRepo, routeStopRepo);
        featureBuilder = new MlFeatureVectorBuilder();
        enricher = new TransferArrivalEnricher(props, mlProps, busPort, mlPort,
                resolver, featureBuilder, collectorProps, clock);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private SubPathResult busSubPath(String startLocalStationID, String busLocalBlID, String busNo) {
        return new SubPathResult(
                2, 10, 1500.0,
                List.of(new LaneResult("360", busNo, null, 1, null, busLocalBlID)),
                Collections.emptyList(),
                "출발", "도착",
                127.0, 37.5, 127.01, 37.51,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null,
                startLocalStationID, null, null, null, null, null, null
        );
    }

    private SubPathResult walkSubPath(int sectionTime) {
        return new SubPathResult(
                3, sectionTime, 100.0,
                Collections.emptyList(), Collections.emptyList(),
                "A", "B", 127.0, 37.5, 127.01, 37.51,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null,
                null, null, null, null, null, null, null
        );
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
        Integer pt2 = predictTime2 < 0 ? null : predictTime2;
        return new BusArrivalInfo(
                // 공통 15
                routeId, "360", "360", "1111", null, "정류장",
                5, null, 3, 10,
                mkTm,
                null, null, null, null,
                // 버스1 기본 9
                "1분", "v1", null, 0, 3, "현재정류장", "0", "0", "0",
                // 버스1 예측+계수 8
                predictTime1, null, null, null,
                null, null, null, null,
                // 버스1 구간+혼잡 6
                null, null,
                null, null, null, null,
                // 버스1 다음정류소 4
                null, null, null, null,
                // 버스1 주요정류소 9
                null, null, null,
                null, null, null,
                null, null, null,
                // 버스2 기본 9
                "msg2", "v2", null, 0, 5, "다음정류장", "0", "0", "0",
                // 버스2 예측+계수 8
                pt2, null, null, null,
                null, null, null, null,
                // 버스2 구간+혼잡 6
                null, null,
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
    void TC_routeId_매핑_실패_UNSUPPORTED_ROUTE() {
        props.setEnabled(true);
        when(stopRepo.findByStopId("1111")).thenReturn(Optional.of(busStop("1111")));
        when(routeRepo.findByRouteId(any())).thenReturn(Optional.empty());
        when(routeRepo.findByRouteNameIn(any())).thenReturn(List.of());
        RouteSearchResult input = resultWith(List.of(busSubPath("1111", "unknownRoute", "unknownBus")));

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
        when(busPort.getArrInfoByStop(stopId)).thenReturn(List.of(ai));

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
        collectorProps.setTargetRouteNames(List.of("360"));

        String routeId = "100100360";
        String stopId = "1111";

        when(stopRepo.findByStopId(stopId)).thenReturn(Optional.of(busStop(stopId)));
        when(routeRepo.findByRouteId(routeId)).thenReturn(Optional.of(busRoute(routeId, "360")));
        when(routeStopRepo.findByRouteIdAndStopId(routeId, stopId))
                .thenReturn(List.of(routeStop(routeId, stopId, 5)));

        // mkTm = KST 12:55:00, predictTime1 = 60s → 12:56 KST (user arrives 13:00 → 이미 지남)
        String mkTm = "20260606125500";
        BusArrivalInfo ai = arrivalInfo(routeId, mkTm, 60, 60); // both buses already passed
        when(busPort.getArrInfoByStop(stopId)).thenReturn(List.of(ai));

        // position: sectOrd=3, targetSeq=5, isRunYn=1
        BusPositionInfo pos = new BusPositionInfo(
                "veh1", 60, 3, 500.0, 10000.0, "0", "s1",
                "20260606130001", "AB1234", 0, 120, "prevStop",
                null, null, "0", "0", 1000.0, "nextStop",
                2, "turnStop", 127.01, 37.51, "1"
        );
        when(busPort.getBusPosByRtid(routeId)).thenReturn(List.of(pos));

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
    void TC_같은_stopId_dedup_1회만_호출() {
        props.setEnabled(true);
        String routeId1 = "100100360";
        String routeId2 = "100100143";
        String stopId = "1111";

        when(stopRepo.findByStopId(stopId)).thenReturn(Optional.of(busStop(stopId)));
        when(routeRepo.findByRouteId(routeId1)).thenReturn(Optional.of(busRoute(routeId1, "360")));
        // routeId2 never resolved — wave1 gets UPSTREAM_UNAVAILABLE (wave0 has no boardable lane)
        // routeStopRepo default returns empty list — no stub needed

        when(busPort.getArrInfoByStop(stopId)).thenReturn(List.of());

        // 같은 stopId를 가진 두 버스 subPath
        List<SubPathResult> subs = List.of(
                busSubPath(stopId, routeId1, "360"),
                busSubPath(stopId, routeId2, "143")
        );
        PathInfo info = new PathInfo(3600, 2000, 0, 0, "출발", "도착", null);
        RouteSearchResult input = new RouteSearchResult(0, 2, 0, 0, 0,
                List.of(new PathResult(1, info, subs, Collections.emptyList())));

        enricher.enrich(input, NOW);

        // 같은 stopId → 1회만
        verify(busPort, times(1)).getArrInfoByStop(stopId);
    }

    @Test
    void TC_ML_disabled_MODEL_UNAVAILABLE() {
        props.setEnabled(true);
        mlProps.setEnabled(false);
        collectorProps.setTargetRouteNames(List.of("360"));

        String routeId = "100100360";
        String stopId = "1111";

        when(stopRepo.findByStopId(stopId)).thenReturn(Optional.of(busStop(stopId)));
        when(routeRepo.findByRouteId(routeId)).thenReturn(Optional.of(busRoute(routeId, "360")));
        when(routeStopRepo.findByRouteIdAndStopId(routeId, stopId))
                .thenReturn(List.of(routeStop(routeId, stopId, 5)));

        // 이미 지나간 버스 (no REALTIME)
        String mkTm = "20260606125500";
        BusArrivalInfo ai = arrivalInfo(routeId, mkTm, 60, 60); // both buses already passed
        when(busPort.getArrInfoByStop(stopId)).thenReturn(List.of(ai));

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
        when(busPort.getArrInfoByStop(stopId)).thenReturn(List.of(ai));

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
        collectorProps.setTargetRouteNames(List.of("360"));

        String routeId = "100100360";
        String stopId = "1111";

        when(stopRepo.findByStopId(stopId)).thenReturn(Optional.of(busStop(stopId)));
        when(routeRepo.findByRouteId(routeId)).thenReturn(Optional.of(busRoute(routeId, "360")));
        when(routeStopRepo.findByRouteIdAndStopId(routeId, stopId))
                .thenReturn(List.of(routeStop(routeId, stopId, 5)));

        // all buses passed
        String mkTm = "20260606125500";
        BusArrivalInfo ai = arrivalInfo(routeId, mkTm, 60, 60); // both buses already passed
        when(busPort.getArrInfoByStop(stopId)).thenReturn(List.of(ai));

        // position: no valid vehicles (sectOrd > targetSeq)
        BusPositionInfo pos = new BusPositionInfo(
                "veh1", 60, 10, 500.0, 10000.0, "0", "s1",
                "20260606130001", "AB1234", 0, 120, "prevStop",
                null, null, "0", "0", 1000.0, "nextStop",
                2, "turnStop", 127.01, 37.51, "1"
        );
        when(busPort.getBusPosByRtid(routeId)).thenReturn(List.of(pos));

        RouteSearchResult input = resultWith(List.of(busSubPath(stopId, routeId, "360")));
        RouteSearchResult result = enricher.enrich(input, NOW);

        TransferArrival ta = result.paths().get(0).subPaths().get(0).transferArrivals().get(0);
        assertThat(ta.status()).isEqualTo(TransferArrival.Status.NO_VEHICLE);
    }

    @Test
    void TC_equality_차량_sectOrd_equals_targetSeq_포함() {
        props.setEnabled(true);
        mlProps.setEnabled(true);
        collectorProps.setTargetRouteNames(List.of("360"));

        String routeId = "100100360";
        String stopId = "1111";

        when(stopRepo.findByStopId(stopId)).thenReturn(Optional.of(busStop(stopId)));
        when(routeRepo.findByRouteId(routeId)).thenReturn(Optional.of(busRoute(routeId, "360")));
        when(routeStopRepo.findByRouteIdAndStopId(routeId, stopId))
                .thenReturn(List.of(routeStop(routeId, stopId, 5)));

        // all buses passed
        String mkTm = "20260606125500";
        BusArrivalInfo ai = arrivalInfo(routeId, mkTm, 60, 60); // both buses already passed
        when(busPort.getArrInfoByStop(stopId)).thenReturn(List.of(ai));

        // sectOrd == targetSeq = 5 (equality case: remaining_stop_count=0)
        BusPositionInfo pos = new BusPositionInfo(
                "veh1", 60, 5, 500.0, 1000.0, "0", "s1",
                "20260606130001", "AB1234", 0, 120, "prevStop",
                null, null, "0", "0", 1000.0, "nextStop",
                2, "turnStop", 127.01, 37.51, "1"
        );
        when(busPort.getBusPosByRtid(routeId)).thenReturn(List.of(pos));

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
    void TC_deadline_초과_시_외부호출_없이_ERROR() {
        props.setEnabled(true);
        props.setDeadlineMs(100);

        // clock: enrich 진입 시 NOW(deadline=NOW+100ms), wave0 체크 시 NOW+2s(초과)
        java.time.Clock deadlineClock = mock(java.time.Clock.class);
        when(deadlineClock.instant()).thenReturn(NOW, NOW.plusSeconds(2));
        TransferArrivalEnricher deadlineEnricher = new TransferArrivalEnricher(
                props, mlProps, busPort, mlPort, resolver, featureBuilder, collectorProps, deadlineClock);

        RouteSearchResult input = resultWith(List.of(busSubPath("1111", "route1", "360")));
        RouteSearchResult result = deadlineEnricher.enrich(input, NOW);

        TransferArrival ta = result.paths().get(0).subPaths().get(0).transferArrivals().get(0);
        assertThat(ta.status()).isEqualTo(TransferArrival.Status.ERROR);
        // deadline 초과 → 외부 호출 전부 생략
        verifyNoInteractions(busPort, mlPort);
    }
}
