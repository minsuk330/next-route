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
 * @param trigger            앱이 leg 를 넘긴 근거(선택, 문자열). 검증하지 않는다 — 모르는 값·다른 타입은 저장하지 않을 뿐 요청은 그대로 받는다
 */
public record ApplyJourneyEventRequest(
        @NotNull UUID eventId,
        @NotNull JourneyEventType type,
        @NotNull @Positive Long baseCommandVersion,
        @NotNull @Positive Integer routeRevision,
        @PositiveOrZero Integer fromLegIndex,
        @PositiveOrZero Integer toLegIndex,
        Instant clientOccurredAt,
        // 문자열이 아니어도 400 이 아니라 "없음"으로 받으려고 JsonNode 로 읽는다. 문서에는 문자열로 보인다.
        @io.swagger.v3.oas.annotations.media.Schema(type = "string", nullable = true,
                description = "leg 를 넘긴 근거. 기록용이며 모르는 값은 저장하지 않는다",
                allowableValues = {"WALK_PROGRESS", "STOP_NEAR", "SPEED_ON_LINE", "BUS_DEPARTED_MOVING",
                        "MANUAL_LIVE_ACTIVITY", "MANUAL_APP", "MISSED_SAID_BOARDED"})
        com.fasterxml.jackson.databind.JsonNode trigger
) {

    public JourneyCommand toCommand() {
        return new JourneyCommand(eventId, type, baseCommandVersion, routeRevision,
                fromLegIndex, toLegIndex, clientOccurredAt,
                // 문자열이 아니면(객체·배열·숫자) 없는 것으로 본다 — 타입이 틀렸다고 400 을 내지 않는다.
                trigger != null && trigger.isTextual() ? trigger.asText() : null);
    }

    /** 종료·lease·reroute는 각각의 전용 API/서버 경로만 사용한다. */
    @JsonIgnore
    @AssertTrue(message = "events API는 CLIENT_LEG_TRANSITION_DETECTED만 허용한다")
    public boolean isClientTransitionEvent() {
        return type == null || type == JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED;
    }
}
