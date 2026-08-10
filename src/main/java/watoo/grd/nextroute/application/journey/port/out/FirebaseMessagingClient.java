package watoo.grd.nextroute.application.journey.port.out;

/**
 * FCM 호출의 유일한 창구. SDK 타입을 이 인터페이스 밖으로 내보내지 않는다.
 *
 * <p>{@code FirebaseMessaging.getInstance()} 가 static 이라 그대로 쓰면 발송 결과 분류를 단위
 * 테스트로 잠글 수 없다. 경계를 하나 두어 발송기 쪽 규칙을 SDK 없이 검증한다.
 *
 * <p>한 번에 <b>기기 한 대</b>다. 표는 사용자당 여러 token 을 허용하지만 알림은 가장 최근에
 * 등록한 한 대에만 간다({@code UserPushTokenRepository} 의 발송 대상 조회).
 */
public interface FirebaseMessagingClient {

    /**
     * token 하나에 보낸다.
     *
     * @return 성공 여부와 provider 가 준 오류 코드
     * @throws watoo.grd.nextroute.application.journey.exception.NotificationSendException
     *         호출 자체가 실패했을 때(타임아웃 등). 전부 일시 실패다
     */
    PushResult send(PushMessage message, String token);

    /**
     * 발송 결과.
     *
     * @param errorCode provider 가 준 {@code MessagingErrorCode} 이름. 성공이면 null
     */
    record PushResult(boolean success, String errorCode) {

        public static PushResult ok() {
            return new PushResult(true, null);
        }

        public static PushResult failed(String errorCode) {
            return new PushResult(false, errorCode);
        }
    }
}
