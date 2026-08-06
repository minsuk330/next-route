package watoo.grd.nextroute.domain.journey.entity;

/**
 * 여정 알림 한 건의 발송 상태. 값 목록은 V40 의
 * {@code journey_notification_status_check} 가 소유한다.
 *
 * <p>{@code bus_alert_notification}(V28)의 상태 기계를 그대로 따른다. FAILED 와 DEAD 를 나누는
 * 이유는 운영에서 볼 것이 다르기 때문이다 — FAILED 는 "보낼 수 없는 알림"이고 DEAD 는
 * "보낼 수 있었는데 계속 실패한 알림"이라 사람이 봐야 한다.
 */
public enum JourneyNotificationStatus {

    /** 발송 대기. relay 가 {@code scheduled_at} 이 지난 것을 집어 간다. */
    PENDING,

    /** relay 가 집어 가 발송 중. 워커가 죽으면 회수 대상이 된다. */
    PROCESSING,

    SENT,

    /** permanent 실패(종결). 재시도 대상 아님. */
    FAILED,

    /** transient 재시도 소진 → 수동 개입 대기. */
    DEAD,

    /** 여정이 끝났거나 만료됐거나 더 새로운 알림에 밀려남. */
    CANCELED
}
