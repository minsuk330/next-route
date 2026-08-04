package watoo.grd.nextroute.application.journey.dto;

/**
 * 여정 한 건을 평가한 결과.
 *
 * @param journeyId     평가한 여정
 * @param routeRevision 계산에 쓴 경로 개정본. 저장 시점에 개정본이 바뀌었으면 그 결과는 버려진다
 * @param batch         판정과 예측
 * @param changed       앱에 보일 값이 직전과 달라졌는지. 여정 평가 번호를 올릴지 가른다
 */
public record JourneyEvaluationOutcome(
        Long journeyId,
        int routeRevision,
        TransferAssessmentBatch batch,
        boolean changed
) {
}
