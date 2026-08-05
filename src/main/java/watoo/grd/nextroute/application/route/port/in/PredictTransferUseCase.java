package watoo.grd.nextroute.application.route.port.in;

import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;

import java.time.Instant;

public interface PredictTransferUseCase {

    /**
     * 어떤 근거까지 쓸지.
     *
     * <p>부르는 쪽이 정한다. 사용자가 정류장에 닿을 시각이 한참 뒤라면 실시간 도착 정보는 쓸모가
     * 없다 — 그 API 는 지금 접근 중인 다음 한두 대만 알려 주므로, 20분 뒤에 탈 차량은 목록에
     * 없다. 그때 실시간을 부르면 답이 없을 호출을 하는 셈이다.
     */
    enum Mode {
        /** 실시간 우선, 탈 수 있는 차량을 못 잡으면 모델. 기본값이다. */
        REALTIME_THEN_MODEL,
        /** 실시간을 건너뛰고 모델만 쓴다. 앵커가 실시간 시야 밖일 때. */
        MODEL_ONLY
    }

    /**
     * 단일 (stopId, routeId, seq) 버스가 userArrivalAt 기준 언제 오는지 예측.
     *
     * @param seq nullable — 없으면 resolver로 해석.
     */
    default TransferPredictionResult predict(String stopId, String routeId, Integer seq,
                                             Instant userArrivalAt) {
        return predict(stopId, routeId, seq, userArrivalAt, Mode.REALTIME_THEN_MODEL);
    }

    TransferPredictionResult predict(String stopId, String routeId, Integer seq,
                                     Instant userArrivalAt, Mode mode);
}
