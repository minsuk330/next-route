package watoo.grd.nextroute.application.notification.exception;

import lombok.Getter;

/**
 * 토스 기능성 메시지 발송 실패.
 * permanent=true: 400(INVALID_PARAMETER)/401/403 등 재시도 가치 없는 실패(템플릿·권한·동의 미완).
 * permanent=false: 429/5xx/timeout 등 transient → 백오프 재시도 대상.
 */
@Getter
public class TossMessengerException extends RuntimeException {

    private final boolean permanent;

    public TossMessengerException(String message, boolean permanent) {
        super(message);
        this.permanent = permanent;
    }

    public TossMessengerException(String message, boolean permanent, Throwable cause) {
        super(message, cause);
        this.permanent = permanent;
    }
}
