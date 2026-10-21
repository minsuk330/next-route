package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import watoo.grd.nextroute.application.journey.dto.JourneyProgressOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;

/**
 * 진행 요약 응답.
 *
 * <p>결과가 무엇이든 최신 스냅샷을 함께 싣는다. 반영되지 않은 이유를 앱이 따로 물어보지 않아도
 * 화면을 맞출 수 있어야 한다.
 *
 * <p><b>{@code journey.eta} 는 싣지 않는다 — 늘 null 이다(#470).</b> 다른 스냅샷 응답과 달리 이 응답만
 * 예측을 합성하지 않는다. 여정마다 15초(하차 임박 5초)에 한 번 오는 가장 잦은 요청이기 때문이다. 앱은 이 응답으로
 * 스냅샷을 갈아끼울 때 <b>직전 {@code eta} 를 그대로 둔다</b>. 새 예측은 조회·heartbeat·이벤트 응답이 싣는다.
 *
 * @param result  {@code APPLIED} 반영 · {@code DUPLICATE} 재전송 · {@code STALE} 역순
 * @param journey 처리 후의 여정 상태. {@code eta} 는 비어 있다
 */
public record JourneyProgressResponse(
        JourneyProgressOutcome.Result result,
        JourneySnapshot journey
) {

    public static JourneyProgressResponse from(JourneyProgressOutcome outcome) {
        return new JourneyProgressResponse(outcome.result(), outcome.journey());
    }
}
