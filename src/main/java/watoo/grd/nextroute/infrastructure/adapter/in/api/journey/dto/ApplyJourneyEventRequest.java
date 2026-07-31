package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;

import java.util.UUID;

/**
 * 여정 명령 요청 본문.
 *
 * <p>{@code eventId} 는 앱이 만들어 보낸다. 서버가 만들면 재전송을 구분할 수 없다 — 응답을 못
 * 받아 다시 보내는 것이 바로 그 상황이기 때문이다.
 *
 * @param eventId            명령 식별자. 재전송이면 같은 값으로 다시 보낸다
 * @param type               명령 종류
 * @param baseCommandVersion 앱이 보고 있던 명령 번호
 * @param routeRevision      앱이 보고 있던 경로 개정본
 * @param routeOptionId      탈 노선을 고르는 명령에서만 채운다
 */
public record ApplyJourneyEventRequest(
        @NotNull UUID eventId,
        @NotNull JourneyEventType type,
        @NotNull @PositiveOrZero Long baseCommandVersion,
        @NotNull @PositiveOrZero Integer routeRevision,
        Long routeOptionId
) {

    public JourneyCommand toCommand() {
        return new JourneyCommand(eventId, type, baseCommandVersion, routeRevision, routeOptionId);
    }
}
