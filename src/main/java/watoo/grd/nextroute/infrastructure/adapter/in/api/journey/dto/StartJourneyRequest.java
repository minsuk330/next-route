package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import jakarta.validation.constraints.NotBlank;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;

/**
 * 여정 시작 요청 본문.
 *
 * <p>{@code replace} 는 본문이 아니라 쿼리 파라미터로 받는다. 기존 여정을 끝내는 동작이라
 * 요청 줄만 봐도 드러나야 한다.
 */
public record StartJourneyRequest(
        @NotBlank String searchSessionId,
        @NotBlank String pathKey
) {

    public StartJourneyCommand toCommand(boolean replace) {
        return new StartJourneyCommand(searchSessionId, pathKey, replace);
    }
}
