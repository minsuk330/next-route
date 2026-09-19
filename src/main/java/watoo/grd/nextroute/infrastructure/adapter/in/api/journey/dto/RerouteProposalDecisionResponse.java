package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

/**
 * 거절·무시의 응답(journey-reroute-nextstop PR-3).
 *
 * <p>여정의 <b>알림 상태</b>를 함께 돌려준다. 앱이 "이제 이 여정에서는 제안이 안 옵니다" 같은
 * 안내를 스스로 판단하지 않게 하려는 것이다 — 규칙(수락 1회, 거절+미체크 종료, 무시 상한)이
 * 서버와 앱 두 벌이 되면 어긋나는 날 사용자에게 거짓말을 한다.
 *
 * @param closed             이번 호출이 <b>실제로</b> 제안을 닫았는가. 재전송이면 false 지만
 *                           응답은 여전히 {@code 200} 이다(멱등)
 * @param rerouteTerminated  이 여정의 재탐색 알림이 끝났는가
 * @param rerouteAlertOptIn  거절했지만 다음 제안은 계속 받기로 했는가
 */
public record RerouteProposalDecisionResponse(
        boolean closed,
        boolean rerouteTerminated,
        boolean rerouteAlertOptIn) {
}
