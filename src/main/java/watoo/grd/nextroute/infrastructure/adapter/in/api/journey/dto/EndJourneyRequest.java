package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;

import java.util.UUID;

/**
 * 여정을 끝내는 요청.
 *
 * <p>종료도 다른 command와 같은 멱등성·version 계약을 사용한다. 오프라인 재전송은 같은
 * {@code eventId}를 유지하고 충돌 시 최신 snapshot으로 수렴한다.
 */
public record EndJourneyRequest(
        @NotNull UUID eventId,
        @NotNull @Positive Long baseCommandVersion,
        @NotNull @Positive Integer routeRevision) {

    public JourneyCommand toCommand(JourneyEventType type) {
        return new JourneyCommand(
                eventId,
                type,
                baseCommandVersion,
                routeRevision,
                null,
                null,
                null);
    }
}
