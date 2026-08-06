package watoo.grd.nextroute.domain.journey.entity;

/**
 * 알림이 가리키는 대상의 종류. 값 목록은 V40 의
 * {@code journey_notification_scope_type_check} 가 소유한다.
 *
 * <p>{@code scope_id} 의 뜻이 종류마다 다르다 — TRANSFER 는 {@code transfer_index},
 * LEG 는 {@code leg_index}, JOURNEY 는 0 이다.
 *
 * <p>이번 PR 은 TRANSFER 만 쓴다. 나머지 둘은 PR4 가 쓰는데 PR4 에 마이그레이션이 없어
 * DB CHECK 를 지금 열어 뒀고, 여기도 같이 연다.
 */
public enum JourneyNotificationScope {

    /** 환승 하나. {@code scope_id = transfer_index}. */
    TRANSFER,

    /** 여정 단계 하나. {@code scope_id = leg_index}. */
    LEG,

    /** 여정 전체. {@code scope_id = 0}. */
    JOURNEY
}
