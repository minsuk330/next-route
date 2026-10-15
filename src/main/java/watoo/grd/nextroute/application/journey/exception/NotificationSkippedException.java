package watoo.grd.nextroute.application.journey.exception;

import lombok.Getter;

/**
 * 보내지 않기로 한 알림. 실패가 아니라 "보낼 필요가 없어졌다"라 relay 가 {@code CANCELED} 로 끝낸다.
 *
 * <p>예: 조용한 갱신(#428)인데 받는 기기가 조용한 채널을 안 만든 Android 라, 보내면 오히려 울린다.
 *
 * @see NotificationSendException 재시도·실패로 끝나는 쪽
 */
@Getter
public class NotificationSkippedException extends RuntimeException {

    /** {@code last_failure_reason} 에 남는 사유. 지표 태그로도 쓴다 — 값 종류를 늘리지 않는다. */
    private final String reason;

    public NotificationSkippedException(String reason) {
        super(reason);
        this.reason = reason;
    }
}
