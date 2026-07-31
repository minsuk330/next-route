package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import watoo.grd.nextroute.application.journey.dto.JourneyEventOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;

/**
 * 명령 처리 결과.
 *
 * <p>성공이든 아니든 여정 상태를 함께 싣는다. 앱이 결과만 받고 상태를 다시 물어야 한다면 그
 * 왕복 동안 화면이 틀린 채로 남는다.
 */
public record JourneyEventResponse(
        JourneyEventResult result,
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
        JourneySnapshot journey
) {

    public static JourneyEventResponse from(JourneyEventOutcome outcome) {
        return new JourneyEventResponse(
                outcome.result(), outcome.rejectionReason(), outcome.journey());
    }
}
