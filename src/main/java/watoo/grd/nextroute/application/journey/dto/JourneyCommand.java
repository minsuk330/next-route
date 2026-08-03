package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;

import java.util.UUID;

/**
 * 여정에 보내는 명령 하나.
 *
 * <p>{@code eventId} 는 앱이 만든다. 지하철에서 응답을 못 받은 앱이 같은 전이를 다시 보내도 두 번
 * 반영되지 않게 하는 열쇠다.
 *
 * <p>{@code baseCommandVersion} 과 {@code routeRevision} 은 "내가 이 상태를 보고 눌렀다"는 진술이다.
 * 그 사이 상태가 바뀌었다면 서버는 명령을 적용하지 않고 최신 상태를 돌려준다 — 사용자가 보고
 * 누른 화면과 서버가 아는 사실이 다르면, 무엇을 의도했는지 서버가 알 수 없기 때문이다.
 *
 * @param eventId            명령 식별자. 재전송 판정의 기준
 * @param type               명령 종류
 * @param baseCommandVersion 앱이 들고 있던 명령 번호
 * @param routeRevision      앱이 들고 있던 경로 개정본 번호
 * @param fromLegIndex       앱이 경계를 통과했다고 판단한 현재 leg
 * @param toLegIndex         다음으로 활성화할 leg. 1차에서는 반드시 from+1
 * @param clientOccurredAt   기기 관측 시각. 감사용이며 서버 상태 순서의 기준으로 쓰지 않는다
 */
public record JourneyCommand(
        UUID eventId,
        JourneyEventType type,
        long baseCommandVersion,
        int routeRevision,
        Integer fromLegIndex,
        Integer toLegIndex,
        java.time.Instant clientOccurredAt
) {
}
