package watoo.grd.nextroute.application.journey.port.out;

import watoo.grd.nextroute.application.journey.dto.BoardingOutcome;
import watoo.grd.nextroute.application.journey.dto.BoardingQuery;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Instant;

/**
 * 값만 받아 <b>승차 시각</b>을 추정한다. DB 를 읽지 않는다.
 *
 * <p>{@link TransferTargetArrivalPredictor} 와 하는 일은 같지만 받는 것이 다르다. 그쪽은 저장된
 * {@code JourneyLeg} 를 받아 내부에서 선택 노선을 DB 로 읽는다. 재탐색이 방금 검색해 온 후보
 * 경로는 <b>DB 에 없으므로</b> 그 모양으로는 물어볼 수가 없다. 그래서 판정 규칙만 여기로 떼고,
 * DB 읽기는 부르는 쪽에 남긴다.
 */
public interface BoardingTimeEstimator {

    boolean supports(JourneyLegType waitType);

    /**
     * @param anchorAt 사용자가 그 정류장에서 탈 준비를 마치는 시각. <b>now 로 대신하지 않는다</b> —
     *                 사용자가 닿기 전에 지나갈 차를 답으로 내놓게 된다
     * @param budgetMs 이 한 번의 조회에 쓸 수 있는 시간(ms)
     * @return 탈 수 있는 차가 없거나 근거가 없으면 값 없는 결과.
     *         "막혀서 못 물어봤다"와 "물어봤지만 근거가 없다"는 {@code blocked} 로 가른다
     *
     * <p><b>예외를 삼키지 않는다.</b> 외부 호출이 터지면 그대로 올린다 — 지금 두 예측기가 그렇게
     * 동작하고, 여기서 삼키면 위임하는 순간 여정 평가 경로의 동작이 바뀐다. 재탐색 쪽은 후보마다
     * {@code try/catch} 로 감싼다.
     */
    BoardingOutcome estimate(BoardingQuery query, Instant anchorAt, Instant now, long budgetMs);
}
