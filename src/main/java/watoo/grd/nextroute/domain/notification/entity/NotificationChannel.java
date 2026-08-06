package watoo.grd.nextroute.domain.notification.entity;

/**
 * 알림을 보내는 길. 값 목록은 V40 의 {@code journey_notification_channel_check} 가 소유한다.
 *
 * <p>journey 패키지가 아니라 notification 도메인에 둔다 — PR3 의 FCM 어댑터가 journey 에
 * 의존하지 않게 하기 위해서다.
 *
 * <p><b>FCM 은 여기에 없다.</b> FCM 은 채널이 아니라 provider 고, 그것은
 * {@code user_push_token.provider}(V39)가 이미 갖는다. 채널은 "어느 길로 보내는가"(토스
 * 메신저냐 네이티브 푸시냐)이고, 그 길 안에서 어느 중계자를 쓰는지는 token 이 안다.
 */
public enum NotificationChannel {

    /** 토스 메신저. 기존 버스 알림이 쓰는 길. */
    TOSS,

    /** 기기 네이티브 푸시. provider 는 {@code user_push_token} 이 안다. */
    PUSH
}
