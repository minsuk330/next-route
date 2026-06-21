package watoo.grd.nextroute.application.route.dto;

import java.time.Instant;

/**
 * 단일 환승 예측 결과. wave 전용 필드(laneIndex/basisLaneIndex/conditional)는 제외한 slim 형태.
 *
 * <p>성공(REALTIME/MODEL)이면 predictedArrivalAt/waitSeconds/boardable 채움.
 * boardable은 선택된 버스가 userArrivalAt 이후 도착하는지(탑승가능). waitSeconds는 부호 有(음수=이미 지나감).
 * 비성공이면 predicted*·boardable null.
 */
public record TransferPredictionResult(
        String stopId,
        String routeId,
        Integer seq,
        TransferArrival.Source source,
        TransferArrival.Status status,
        Instant calculatedAt,
        Instant userArrivalAt,
        Instant predictedArrivalAt,
        Long waitSeconds,
        Boolean boardable,
        String vehicleId,
        String modelVersion
) {
    public static TransferPredictionResult noResult(
            String stopId, String routeId, Integer seq,
            TransferArrival.Source source, TransferArrival.Status status,
            Instant calculatedAt, Instant userArrivalAt) {
        return new TransferPredictionResult(stopId, routeId, seq, source, status,
                calculatedAt, userArrivalAt, null, null, null, null, null);
    }

    public static TransferPredictionResult available(
            String stopId, String routeId, Integer seq, TransferArrival.Source source,
            Instant calculatedAt, Instant userArrivalAt, Instant predictedArrivalAt,
            String vehicleId, String modelVersion) {
        long wait = predictedArrivalAt.getEpochSecond() - userArrivalAt.getEpochSecond();
        boolean boardable = !predictedArrivalAt.isBefore(userArrivalAt);
        return new TransferPredictionResult(stopId, routeId, seq, source,
                TransferArrival.Status.AVAILABLE, calculatedAt, userArrivalAt,
                predictedArrivalAt, wait, boardable, vehicleId, modelVersion);
    }
}
