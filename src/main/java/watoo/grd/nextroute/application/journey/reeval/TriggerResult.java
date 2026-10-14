package watoo.grd.nextroute.application.journey.reeval;

import java.util.Set;

/**
 * 재평가 요청 결과.
 *
 * @param delivered 반영된 여정(그림자 모드면 반영했다고 치는 여정). 바로 앞당겼든 처리 중이라 표시만
 *                  했든 이 여정은 바뀐 데이터로 다시 평가된다
 * @param deferred  이번에 반영하지 못한 여정 — 상한 초과, 평가기 포화로 건너뜀, DB 실패. 이벤트를
 *                  잃지 않으려면 다음 주기에 다시 보내야 한다
 */
public record TriggerResult(Set<Long> delivered, Set<Long> deferred) {

    public static TriggerResult nothing() {
        return new TriggerResult(Set.of(), Set.of());
    }
}
