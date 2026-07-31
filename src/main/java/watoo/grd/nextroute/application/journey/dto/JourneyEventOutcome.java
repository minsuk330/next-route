package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;

/**
 * 명령 하나를 처리한 결과.
 *
 * <p>어떤 결과든 <b>최신 상태를 함께</b> 돌려준다. 거절이나 충돌일수록 더 그렇다 — 앱이
 * "그래서 지금 상태가 뭔데?"를 다시 물어야 한다면, 그 왕복 동안 화면은 틀린 채로 남는다.
 *
 * @param result          적용·재전송·충돌·거절
 * @param rejectionReason 거절·충돌 이유. 적용이면 null
 * @param journey         처리 후의 여정 상태
 */
public record JourneyEventOutcome(
        JourneyEventResult result,
        String rejectionReason,
        JourneySnapshot journey
) {
}
