package watoo.grd.nextroute.application.notification.port.out;

import java.util.Map;

/** 토스 기능성 메시지 발송 아웃바운드 포트. */
public interface TossMessengerPort {

    /**
     * 기능성 메시지 1건 발송(mTLS, x-toss-user-key).
     * 실패 시 {@code TossMessengerException}(permanent 여부 포함)을 던진다.
     */
    void sendMessage(long tossUserKey, String templateSetCode, Map<String, Object> context);
}
