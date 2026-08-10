package watoo.grd.nextroute.application.journey.exception;

import lombok.Getter;

/**
 * 여정 알림 발송 실패. {@code TossMessengerException} 과 같은 모양이다.
 *
 * <p>{@code permanent=true}: 토큰이 없거나 권한이 없는 등 재시도해도 달라지지 않는 실패 →
 * 재시도 없이 FAILED.
 *
 * <p>{@code permanent=false}: 429·5xx·타임아웃 등 → 백오프 재시도 대상.
 */
@Getter
public class NotificationSendException extends RuntimeException {

    private final boolean permanent;

    public NotificationSendException(String message, boolean permanent) {
        super(message);
        this.permanent = permanent;
    }

    public NotificationSendException(String message, boolean permanent, Throwable cause) {
        super(message, cause);
        this.permanent = permanent;
    }
}
