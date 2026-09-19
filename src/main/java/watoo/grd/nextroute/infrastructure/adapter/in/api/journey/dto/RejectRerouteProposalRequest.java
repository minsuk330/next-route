package watoo.grd.nextroute.infrastructure.adapter.in.api.journey.dto;

/**
 * 제안을 거절할 때의 본문(journey-reroute-nextstop PR-3).
 *
 * <p>칸이 하나뿐이고 그 하나가 <b>다음 알림을 받을지</b>를 정한다. 거절창의 "계속 알림 받기 ☐"
 * 체크박스가 그대로 이 값이다.
 *
 * @param keepAlerts 켜면 이 여정의 다음 제안도 계속 받는다. <b>기본은 꺼짐</b> — 값이 없으면
 *                   "이제 그만"으로 읽는다. 거절은 그 자체로 "지금 방식이 마음에 안 든다"는
 *                   의사표시라, 모르는 값을 "계속 보내도 된다"로 해석하면 안 된다
 */
public record RejectRerouteProposalRequest(Boolean keepAlerts) {

    public boolean keepAlertsOrDefault() {
        return Boolean.TRUE.equals(keepAlerts);
    }
}
