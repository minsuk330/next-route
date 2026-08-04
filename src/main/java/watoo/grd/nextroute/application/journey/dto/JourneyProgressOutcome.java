package watoo.grd.nextroute.application.journey.dto;

/**
 * progress 처리 결과와 그 시점의 여정 상태.
 *
 * <p>결과가 무엇이든 최신 스냅샷을 함께 준다. 앱은 이것으로 화면을 맞추면 되고, 왜 반영되지
 * 않았는지를 따로 물어볼 필요가 없다.
 *
 * @param result  반영·재전송·역순 중 하나
 * @param journey 처리 후의 여정 상태
 */
public record JourneyProgressOutcome(
        Result result,
        JourneySnapshot journey
) {

    public enum Result {
        /** 더 최신 관측이라 반영했다. {@code evaluationVersion} 이 하나 올라간다. */
        APPLIED,
        /** 이미 반영한 progress 를 다시 받았다. 상태는 그대로다. */
        DUPLICATE,
        /** 지금 값보다 오래된 관측이다. 상태는 그대로다. */
        STALE
    }
}
