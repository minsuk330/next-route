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
    RIDE_INTERIOR
}
