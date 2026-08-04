package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import watoo.grd.nextroute.application.journey.dto.JourneyProgressOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;

/**
 * 진행 요약 응답.
 *
 * <p>결과가 무엇이든 최신 스냅샷을 함께 싣는다. 반영되지 않은 이유를 앱이 따로 물어보지 않아도
 * 화면을 맞출 수 있어야 한다.
 *
 * @param result  {@code APPLIED} 반영 · {@code DUPLICATE} 재전송 · {@code STALE} 역순
 * @param journey 처리 후의 여정 상태
 */
public record JourneyProgressResponse(
        JourneyProgressOutcome.Result result,
        JourneySnapshot journey
) {

    public static JourneyProgressResponse from(JourneyProgressOutcome outcome) {
        return new JourneyProgressResponse(outcome.result(), outcome.journey());
    }
}
