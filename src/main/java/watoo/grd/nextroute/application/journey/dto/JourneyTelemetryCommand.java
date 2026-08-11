package watoo.grd.nextroute.application.journey.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 계측 이벤트 하나. 앱이 보낸 값 그대로이며 <b>아직 걸러지지 않았다</b>.
 *
 * <p>{@code detail} 의 허용 키 필터와 {@code alertId} 소유권 확인은 서비스가 한다 — 검증을
 * DTO 에 두면 "무엇을 저장했나"의 근거가 두 곳으로 갈린다.
 */
public record JourneyTelemetryCommand(
        String telemetryId,
        String eventType,
        Instant occurredAt,
        Integer routeRevision,
        Long alertId,
        JsonNode detail
) {
}
