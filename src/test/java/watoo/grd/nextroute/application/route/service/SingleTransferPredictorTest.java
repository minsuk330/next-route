package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.route.config.MlPredictorProperties;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.config.TransferPredictProperties;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlFeatureVector;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPrediction;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPredictionStatus;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SingleTransferPredictorTest {

    static final String MK_TM = "20260606130000";
    static final Instant BASE = BusTimeParser.parse(MK_TM).orElseThrow();

    static final String STOP = "1001";
    static final String ROUTE = "100100118";

    @Mock SearchTimeBusQueryPort busPort;
    @Mock MlArrivalPredictorPort mlPort;
    @Mock TransferStopResolver resolver;
    @Mock BusRouteStopRepository routeStopRepo;
    @Mock PredictionSupportService supportService;

    TransferArrivalProperties transferProps;
    TransferPredictProperties predictProps;
    MlPredictorProperties mlProps;
    SingleTransferPredictor predictor;

    @BeforeEach
    void setUp() {
        transferProps = new TransferArrivalProperties();
        transferProps.setEnabled(true);
        predictProps = new TransferPredictProperties();
        mlProps = new MlPredictorProperties();
        mlProps.setEnabled(true);
        Clock clock = Clock.fixed(BASE, ZoneOffset.UTC);
        predictor = new SingleTransferPredictor(transferProps, predictProps, mlProps,
                busPort, mlPort, new MlFeatureVectorBuilder(), resolver, routeStopRepo, supportService, clock);
    }

    @Test
    @DisplayName("feature 비활성 → DISABLED, 외부콜 0")
    void disabled() {
        transferProps.setEnabled(false);

        TransferPredictionResult r = predictor.predict(STOP, ROUTE, 5, BASE.plusSeconds(60));

        assertThat(r.status()).isEqualTo(TransferArrival.Status.DISABLED);
        verifyNoInteractions(busPort, mlPort);
    }

    @Test
    @DisplayName("제공 seq 조합 불일치 → STOP_MAPPING_FAILED, 외부콜 0")
    void invalidSeq() {
        given(routeStopRepo.existsByRouteIdAndStopIdAndSeq(ROUTE, STOP, 9)).willReturn(false);

        TransferPredictionResult r = predictor.predict(STOP, ROUTE, 9, BASE.plusSeconds(60));

        assertThat(r.status()).isEqualTo(TransferArrival.Status.STOP_MAPPING_FAILED);
        verifyNoInteractions(busPort, mlPort);
    }

    @Test
    @DisplayName("REALTIME boardable → AVAILABLE, waitSeconds 양수")
    void realtimeBoardable() {
        given(routeStopRepo.existsByRouteIdAndStopIdAndSeq(ROUTE, STOP, 5)).willReturn(true);
        given(busPort.getArrInfoByStop(STOP, ROUTE, 5)).willReturn(
                BusQueryResult.ok(List.of(arrival(ROUTE, MK_TM, 300, -1))));

        TransferPredictionResult r = predictor.predict(STOP, ROUTE, 5, BASE.plusSeconds(60));

        assertThat(r.source()).isEqualTo(TransferArrival.Source.REALTIME);
        assertThat(r.status()).isEqualTo(TransferArrival.Status.AVAILABLE);
        assertThat(r.boardable()).isTrue();
        assertThat(r.predictedArrivalAt()).isEqualTo(BASE.plusSeconds(300));
        assertThat(r.waitSeconds()).isEqualTo(240);
        assertThat(r.vehicleId()).isEqualTo("v1");
        verify(mlPort, never()).predict(anyList());
    }

    @Test
    @DisplayName("REALTIME 이미 지나감(없는 boardable) → earliest 반환, boardable=false 음수 wait")
    void realtimeMissed() {
        given(routeStopRepo.existsByRouteIdAndStopIdAndSeq(ROUTE, STOP, 5)).willReturn(true);
        given(busPort.getArrInfoByStop(STOP, ROUTE, 5)).willReturn(
                BusQueryResult.ok(List.of(arrival(ROUTE, MK_TM, 60, -1))));

        TransferPredictionResult r = predictor.predict(STOP, ROUTE, 5, BASE.plusSeconds(120));

        assertThat(r.source()).isEqualTo(TransferArrival.Source.REALTIME);
        assertThat(r.boardable()).isFalse();
        assertThat(r.waitSeconds()).isEqualTo(-60);
    }

    @Test
    @DisplayName("REALTIME 없음 + SUPPORTED + ml on → MODEL earliest")
    void modelFallback() {
        given(routeStopRepo.existsByRouteIdAndStopIdAndSeq(ROUTE, STOP, 5)).willReturn(true);
        given(busPort.getArrInfoByStop(STOP, ROUTE, 5)).willReturn(BusQueryResult.ok(List.of())); // route 도착 없음
        given(supportService.support(ROUTE)).willReturn(PredictionSupportService.Support.SUPPORTED);
        given(busPort.getBusPosByRtid(ROUTE)).willReturn(BusQueryResult.ok(List.of(position("veh1", 3))));
        given(mlPort.predict(anyList())).willAnswer(inv -> {
            List<MlFeatureVector> v = inv.getArgument(0);
            return v.stream().map(x -> new MlPrediction(x.requestId(), MlPredictionStatus.AVAILABLE, 200.0, "v1")).toList();
        });

        TransferPredictionResult r = predictor.predict(STOP, ROUTE, 5, BASE.plusSeconds(60));

        assertThat(r.source()).isEqualTo(TransferArrival.Source.MODEL);
        assertThat(r.status()).isEqualTo(TransferArrival.Status.AVAILABLE);
        assertThat(r.predictedArrivalAt()).isEqualTo(BASE.plusSeconds(200));
        assertThat(r.boardable()).isTrue();
        assertThat(r.vehicleId()).isEqualTo("veh1");
        assertThat(r.modelVersion()).isEqualTo("v1");
    }

    @Test
    @DisplayName("미지원 노선 → UNSUPPORTED_ROUTE, position 콜 0 (realtime만)")
    void unsupportedSkipsModel() {
        given(routeStopRepo.existsByRouteIdAndStopIdAndSeq(ROUTE, STOP, 5)).willReturn(true);
        given(busPort.getArrInfoByStop(STOP, ROUTE, 5)).willReturn(BusQueryResult.ok(List.of()));
        given(supportService.support(ROUTE)).willReturn(PredictionSupportService.Support.UNSUPPORTED);

        TransferPredictionResult r = predictor.predict(STOP, ROUTE, 5, BASE.plusSeconds(60));

        assertThat(r.status()).isEqualTo(TransferArrival.Status.UNSUPPORTED_ROUTE);
        verify(busPort, never()).getBusPosByRtid(any());
        verify(mlPort, never()).predict(anyList());
    }

    @Test
    @DisplayName("UNKNOWN(캐시 미적재) → ML 시도, serving UNSUPPORTED_ROUTE → 그 상태")
    void unknownTriesMlThenServingUnsupported() {
        given(routeStopRepo.existsByRouteIdAndStopIdAndSeq(ROUTE, STOP, 5)).willReturn(true);
        given(busPort.getArrInfoByStop(STOP, ROUTE, 5)).willReturn(BusQueryResult.ok(List.of()));
        given(supportService.support(ROUTE)).willReturn(PredictionSupportService.Support.UNKNOWN);
        given(busPort.getBusPosByRtid(ROUTE)).willReturn(BusQueryResult.ok(List.of(position("veh1", 3))));
        given(mlPort.predict(anyList())).willAnswer(inv -> {
            List<MlFeatureVector> v = inv.getArgument(0);
            return v.stream().map(x -> new MlPrediction(x.requestId(), MlPredictionStatus.UNSUPPORTED_ROUTE, null, "v1")).toList();
        });

        TransferPredictionResult r = predictor.predict(STOP, ROUTE, 5, BASE.plusSeconds(60));

        assertThat(r.status()).isEqualTo(TransferArrival.Status.UNSUPPORTED_ROUTE);
        verify(busPort).getBusPosByRtid(ROUTE);
    }

    @Test
    @DisplayName("ml off → MODEL_UNAVAILABLE, position 콜 0")
    void mlOff() {
        mlProps.setEnabled(false);
        given(routeStopRepo.existsByRouteIdAndStopIdAndSeq(ROUTE, STOP, 5)).willReturn(true);
        given(busPort.getArrInfoByStop(STOP, ROUTE, 5)).willReturn(BusQueryResult.ok(List.of()));
        given(supportService.support(ROUTE)).willReturn(PredictionSupportService.Support.SUPPORTED);

        TransferPredictionResult r = predictor.predict(STOP, ROUTE, 5, BASE.plusSeconds(60));

        assertThat(r.status()).isEqualTo(TransferArrival.Status.MODEL_UNAVAILABLE);
        verify(busPort, never()).getBusPosByRtid(any());
    }

    @Test
    @DisplayName("stop API BLOCKED → BLOCKED, position 콜 0")
    void stopBlocked() {
        given(routeStopRepo.existsByRouteIdAndStopIdAndSeq(ROUTE, STOP, 5)).willReturn(true);
        given(busPort.getArrInfoByStop(STOP, ROUTE, 5)).willReturn(BusQueryResult.blocked());

        TransferPredictionResult r = predictor.predict(STOP, ROUTE, 5, BASE.plusSeconds(60));

        assertThat(r.status()).isEqualTo(TransferArrival.Status.BLOCKED);
        verify(busPort, never()).getBusPosByRtid(any());
    }

    @Test
    @DisplayName("stop API ERROR라도 targetSeq 확정 시 ML fallback 진입")
    void stopErrorStillFallsBackToMl() {
        given(routeStopRepo.existsByRouteIdAndStopIdAndSeq(ROUTE, STOP, 5)).willReturn(true);
        given(busPort.getArrInfoByStop(STOP, ROUTE, 5)).willReturn(BusQueryResult.error());
        given(supportService.support(ROUTE)).willReturn(PredictionSupportService.Support.SUPPORTED);
        given(busPort.getBusPosByRtid(ROUTE)).willReturn(BusQueryResult.ok(List.of(position("veh1", 3))));
        given(mlPort.predict(anyList())).willAnswer(inv -> {
            List<MlFeatureVector> v = inv.getArgument(0);
            return v.stream().map(x -> new MlPrediction(x.requestId(), MlPredictionStatus.AVAILABLE, 200.0, "v1")).toList();
        });

        TransferPredictionResult r = predictor.predict(STOP, ROUTE, 5, BASE.plusSeconds(60));

        assertThat(r.source()).isEqualTo(TransferArrival.Source.MODEL);
        assertThat(r.status()).isEqualTo(TransferArrival.Status.AVAILABLE);
        verify(busPort).getBusPosByRtid(ROUTE);
    }

    @Test
    @DisplayName("seq 미제공 → resolver 단일 후보 사용")
    void resolvesSeqWhenAbsent() {
        given(resolver.resolveSeq(ROUTE, STOP))
                .willReturn(new TransferStopResolver.SeqResolution(List.of(5)));
        given(busPort.getArrInfoByStop(STOP, ROUTE, 5)).willReturn(
                BusQueryResult.ok(List.of(arrival(ROUTE, MK_TM, 300, -1))));

        TransferPredictionResult r = predictor.predict(STOP, ROUTE, null, BASE.plusSeconds(60));

        assertThat(r.seq()).isEqualTo(5);
        assertThat(r.source()).isEqualTo(TransferArrival.Source.REALTIME);
    }

    // ── fixtures ──

    private BusArrivalInfo arrival(String routeId, String mkTm, int pt1, int pt2) {
        Integer p2 = pt2 < 0 ? null : pt2;
        return new BusArrivalInfo(
                routeId, "360", "360", "1111", null, "정류장",
                5, null, 3, 10,
                mkTm, null, null, null, null,
                "1분", "v1", null, 0, 3, "현재정류장", "0", "0", "0",
                pt1, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                "msg2", "v2", null, 0, 5, "다음정류장", "0", "0", "0",
                p2, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    private BusPositionInfo position(String vehicleId, int sectionOrder) {
        return new BusPositionInfo(
                vehicleId, 60, sectionOrder, 500.0, 10000.0, "0", "s1",
                MK_TM, "AB1234", 0, 120, "prevStop",
                null, null, "0", "0", 1000.0, "nextStop",
                2, "turnStop", 127.01, 37.51, "1");
    }
}
