package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;

import java.util.UUID;
import java.time.Instant;

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
 * @param fromLegIndex       앱이 현재라고 확인한 leg
 * @param toLegIndex         다음으로 활성화할 leg
 */
public record ApplyJourneyEventRequest(
        @NotNull UUID eventId,
        @NotNull JourneyEventType type,
        @NotNull @Positive Long baseCommandVersion,
        @NotNull @Positive Integer routeRevision,
        @PositiveOrZero Integer fromLegIndex,
        @PositiveOrZero Integer toLegIndex,
        Instant clientOccurredAt
) {

    public JourneyCommand toCommand() {
        return new JourneyCommand(eventId, type, baseCommandVersion, routeRevision,
                fromLegIndex, toLegIndex, clientOccurredAt);
    }

    /** 종료·lease·reroute는 각각의 전용 API/서버 경로만 사용한다. */
    @JsonIgnore
    @AssertTrue(message = "events API는 CLIENT_LEG_TRANSITION_DETECTED만 허용한다")
    public boolean isClientTransitionEvent() {
        return type == null || type == JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED;
    }
}
