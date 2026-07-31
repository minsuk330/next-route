package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;

import java.util.UUID;

/**
 * 여정을 끝내는 요청.
 *
 * <p>{@code eventId} 는 선택이지만 보내는 편이 낫다. 종료 요청을 보내고 응답을 못 받아 다시
 * 보냈을 때, 값이 있으면 두 번째 요청이 재전송으로 인식돼 "이미 끝난 여정"이라는 오류 대신
 * 처음 결과를 그대로 받는다.
 *
 * <p>{@code baseCommandVersion} 도 선택이다. 비우면 서버가 현재 값을 쓴다 — 종료는 "지금
 * 상태가 무엇이든 끝낸다"에 가까워서, 낡은 번호 때문에 실패하면 오히려 곤란하다.
 */
public record EndJourneyRequest(UUID eventId, Long baseCommandVersion) {

    public JourneyCommand toCommand(JourneyEventType type,
                                    long currentCommandVersion, int routeRevision) {
        return new JourneyCommand(
                eventId != null ? eventId : UUID.randomUUID(),
                type,
                baseCommandVersion != null ? baseCommandVersion : currentCommandVersion,
                routeRevision,
                null);
    }
}
