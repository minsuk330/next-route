package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import jakarta.validation.constraints.NotBlank;
import watoo.grd.nextroute.application.journey.dto.StartTrackCommand;

/**
 * 단독 추적 시작 요청(#265). id 는 전부 OTP GTFS id 다({@code 1:BS_TAGO_SEB121000091} 등).
 *
 * <p>{@code replace} 는 일반 시작과 같이 쿼리 파라미터로 받는다 — 진행 중인 여정(일반 여정 포함)을 끝내는 동작이라
 * 요청 줄만 봐도 드러나야 한다.
 *
 * @param patternCode 방향 조회가 준 값. 힌트일 뿐이라 비워도 된다
 */
public record StartTrackRequest(
        @NotBlank String routeGtfsId,
        @NotBlank String boardStopId,
        @NotBlank String alightStopId,
        String patternCode
) {

    public StartTrackCommand toCommand(boolean replace) {
        return new StartTrackCommand(routeGtfsId, boardStopId, alightStopId, patternCode, replace);
    }
}
