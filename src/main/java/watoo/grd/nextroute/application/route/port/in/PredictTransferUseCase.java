package watoo.grd.nextroute.application.route.port.in;

import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;

import java.time.Instant;

public interface PredictTransferUseCase {

    /**
     * 단일 (stopId, routeId, seq) 버스가 userArrivalAt 기준 언제 오는지 예측.
     *
     * @param seq nullable — 없으면 resolver로 해석.
     */
    TransferPredictionResult predict(String stopId, String routeId, Integer seq, Instant userArrivalAt);
}
