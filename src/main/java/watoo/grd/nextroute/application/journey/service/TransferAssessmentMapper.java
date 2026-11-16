package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;

/**
 * 저장된 환승 행을 판정 값으로 되살린다.
 *
 * <p>두 곳이 같은 변환을 쓴다 — 평가기는 "직전 판정과 달라졌는지" 견주려고, 조회 경로는 앱에
 * 내려보내려고. 변환을 양쪽에 각각 두면 한쪽만 고쳐져 비교와 표시가 어긋난다.
 */
final class TransferAssessmentMapper {

    private TransferAssessmentMapper() {
    }

    /**
     * 저장된 값을 그대로 판정으로 옮긴다. 신선한지는 따지지 않는다.
     *
     * <p>{@code confidence} 는 저장된 문자열을 되살린다. 알 수 없는 값이면 모름으로 둔다.
     */
    static TransferRiskAssessment fromStored(TransferConnection transfer) {
        return new TransferRiskAssessment(
                transfer.getTransferIndex(),
                transfer.getFromLegIndex(),
                transfer.getToLegIndex(),
                transfer.getStatus(),
                transfer.getCurrentLegArrivalAt(),
                transfer.getUserReadyAt(),
                transfer.getNextBusArrivalAt(),
                transfer.getTransferSlackSeconds() == null
                        ? null : transfer.getTransferSlackSeconds().longValue(),
                qualityOf(transfer.getConfidence()),
                transfer.getCalculatedAt(),
                sourceOf(transfer.getSource()),
                transfer.getModelVersion());
    }

    /** 저장된 문자열을 다시 열거값으로. 알 수 없는 값이면 비교에서 "다름"으로 취급된다. */
    static CurrentLegArrival.Source sourceOf(String stored) {
        if (TransferAssessmentWriter.SOURCE_REALTIME.equals(stored)) {
            return CurrentLegArrival.Source.REALTIME_ARRIVAL;
        }
        if (TransferAssessmentWriter.SOURCE_MODEL.equals(stored)) {
            return CurrentLegArrival.Source.MODEL_ARRIVAL;
        }
        if (TransferAssessmentWriter.SOURCE_TIMETABLE.equals(stored)) {
            return CurrentLegArrival.Source.TIMETABLE;
        }
        return null;
    }

    static CurrentLegArrival.Quality qualityOf(String stored) {
        if (stored == null) {
            return CurrentLegArrival.Quality.UNKNOWN;
        }
        try {
            return CurrentLegArrival.Quality.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return CurrentLegArrival.Quality.UNKNOWN;
        }
    }
}
