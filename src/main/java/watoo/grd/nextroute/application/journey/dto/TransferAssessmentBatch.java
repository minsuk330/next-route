package watoo.grd.nextroute.application.journey.dto;

import java.util.List;
import java.util.Map;

/**
 * 환승 판정 한 벌과, 그것을 만들 때 얻은 갈아탈 차량 예측.
 *
 * <p>판정만으로는 부족한 값이 있다. 예측의 신뢰도와 모델 이름은 판정에 합쳐지면서(두 ETA 중 나쁜
 * 쪽을 따르며) 원래 값이 묻힌다. 대기 단계 행에 예측을 그대로 적어 두려면 합치기 전 값이 필요하다.
 *
 * @param assessments        환승 순번 순서
 * @param boardingByLegIndex 갈아탈 차량을 기다리는 단계 순번 → 그 단계의 도착 예측
 * @param upstreamBlocked    환승 하나라도 provider 차단으로 조회를 못 했는지. 다음 평가를 늦춘다
 * @param laneReselection    이 평가가 묶인 버스 대기 중 선택을 옮겼으면 그 결정(#614 PR-2). 판정·예측은 새 노선 기준이다. 없으면 null
 */
public record TransferAssessmentBatch(
        List<TransferRiskAssessment> assessments,
        Map<Integer, TransferTargetArrival> boardingByLegIndex,
        boolean upstreamBlocked,
        LaneReselection laneReselection
) {

    public TransferAssessmentBatch(List<TransferRiskAssessment> assessments,
                                   Map<Integer, TransferTargetArrival> boardingByLegIndex,
                                   boolean upstreamBlocked) {
        this(assessments, boardingByLegIndex, upstreamBlocked, null);
    }

    public static TransferAssessmentBatch empty() {
        return new TransferAssessmentBatch(List.of(), Map.of(), false);
    }

    public TransferAssessmentBatch withLaneReselection(LaneReselection reselection) {
        return new TransferAssessmentBatch(assessments, boardingByLegIndex, upstreamBlocked, reselection);
    }

    public boolean isEmpty() {
        return assessments.isEmpty();
    }
}
