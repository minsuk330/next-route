package watoo.grd.nextroute.application.journey.dto;

/**
 * {@link RerouteOrigin} 이 가리키는 지점의 성격.
 *
 * <p>{@code TRANSFER_WAIT} 는 옛 {@code transferIndex} 가 가리키던 자리(환승 대기 leg 시작)와
 * 같다. {@code RIDE_INTERIOR} 는 진행 중인 탑승 leg 의 중간 정류장 — 옛 모델엔 주소가 아예
 * 없던 자리다(journey-reroute-nextstop PLAN PR-B).
 */
public enum RerouteOriginKind {
    TRANSFER_WAIT,
    RIDE_INTERIOR,
    /**
     * 걷는 중의 현재 위치(#264 보드). 서버는 좌표를 받지 않으므로 진행률로 도보 선 위를 보간한다.
     * 정류장이 아니라 {@code stopId} 가 없고 좌표({@code x}·{@code y})가 주소다.
     */
    CURRENT_POSITION,
    /** 기다리는 정류장·역(#264 보드). 같은 정류장의 다른 노선·경로를 찾는 자리다. */
    WAITING_STOP
}
