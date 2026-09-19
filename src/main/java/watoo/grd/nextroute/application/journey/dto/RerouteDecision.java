package watoo.grd.nextroute.application.journey.dto;

/**
 * 사용자가 제안에 답한 결과(journey-reroute-nextstop PR-3).
 *
 * <p>여정의 알림 상태를 함께 담는 이유 — 규칙(수락 1회, 거절+미체크 종료, 무시 상한)을 앱이 다시
 * 계산하게 하면 두 벌이 되고, 어긋나는 날 사용자에게 거짓말을 한다.
 *
 * @param closed     이번 호출이 <b>실제로</b> 닫았는가. 재전송이면 false(멱등)
 * @param terminated 이 여정의 재탐색 알림이 끝났는가
 * @param optIn      거절했지만 다음 제안은 계속 받기로 했는가
 */
public record RerouteDecision(boolean closed, boolean terminated, boolean optIn) {

    /** 이미 닫혀 있던 제안. 여정 상태는 그대로 읽어 돌려준다. */
    public static RerouteDecision unchanged(boolean terminated, boolean optIn) {
        return new RerouteDecision(false, terminated, optIn);
    }
}
