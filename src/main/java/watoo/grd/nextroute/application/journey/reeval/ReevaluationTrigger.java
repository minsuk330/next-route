package watoo.grd.nextroute.application.journey.reeval;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 실시간 데이터가 바뀐 여정의 다음 평가를 앞당긴다.
 *
 * <p>발행 통로를 이 인터페이스 뒤에 둔다. 지금 구현은 DB 행 하나를 고치는 것으로 끝난다 — 평가기는
 * 어느 인스턴스든 {@code SKIP LOCKED} 로 그 행을 집어 가므로, 인스턴스가 여럿이어도 메시지 큐가
 * 필요 없다(DB 가 공유 대기열이다). DB 쓰기가 병목이 되면 이 자리를 다른 구현으로 바꾼다.
 */
public interface ReevaluationTrigger {

    /**
     * @param journeysByTier 등급별 앞당길 여정. 한 여정이 여러 등급에 있으면 가장 급한 쪽으로 친다
     * @return 무엇이 반영됐고 무엇이 미뤄졌는지. 부르는 쪽은 {@link TriggerResult#deferred()} 에 든
     *         여정의 이벤트를 다음에 다시 보내야 한다(감지 기준선을 갱신하지 말 것)
     */
    TriggerResult request(Map<ReevaluationTier, Set<Long>> journeysByTier);

    /**
     * 처음 본 시각을 함께 넘기는 요청(#446). 구현은 깨우려는 여정 모두(미뤄지는 여정 포함)에 이 시각을 남겨, 그 여정의
     * 다음 평가가 만든 알림이 "처음 본 → FCM 접수" 를 잴 수 있게 한다. 기본 구현은 시각을 버린다.
     *
     * @param observedAt 변화를 처음 받은 시각(수집기가 원천 응답을 받은 순간). null 이면 남기지 않는다
     */
    default TriggerResult request(Map<ReevaluationTier, Set<Long>> journeysByTier, Instant observedAt) {
        return request(journeysByTier);
    }
}
