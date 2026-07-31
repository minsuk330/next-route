package watoo.grd.nextroute.domain.journey.entity;

/**
 * 여정이 왜 끝났는지. 값 목록은 V35 의 {@code journey_end_reason_check} 가 소유한다.
 *
 * <p>{@link JourneyStatus} 는 "끝났다"만 말하고 이 값이 "왜"를 말한다. 같은 {@code CANCELED}
 * 라도 사용자가 접은 것과 새 여정에 밀려난 것은 나중에 따로 세야 한다.
 */
public enum JourneyEndReason {

    /** 목적지 도착. */
    COMPLETED,

    /** 사용자가 직접 종료. */
    USER_CANCELED,

    /** {@code replace=true} 로 시작한 새 여정에 자리를 내줌. */
    REPLACED,

    /** 앱이 lease 갱신을 멈춰 서버가 접음. */
    CLIENT_LEASE_EXPIRED,

    /** 운영자가 종료. */
    ADMIN_CANCELED
}
