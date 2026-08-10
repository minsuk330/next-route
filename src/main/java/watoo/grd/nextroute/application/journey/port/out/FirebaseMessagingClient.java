package watoo.grd.nextroute.application.journey.port.out;

import java.util.List;

/**
 * FCM 호출의 유일한 창구. SDK 타입을 이 인터페이스 밖으로 내보내지 않는다.
 *
 * <p>{@code FirebaseMessaging.getInstance()} 가 static 이라 그대로 쓰면 발송 결과 분류를 단위
 * 테스트로 잠글 수 없다. 경계를 하나 두어 발송기 쪽 규칙(§2.3)을 SDK 없이 검증한다.
 *
 * <p>구현이 지는 책임 셋.
 * <ul>
 *   <li>token 을 500개씩 나눠 보낸다(FCM multicast 상한)</li>
 *   <li>결과를 <b>입력 순서 그대로</b> 다시 합친다 — 어긋나면 엉뚱한 token 이 꺼진다</li>
 *   <li>전체 호출이 예산을 넘지 않게 한다. chunk 별 예산이 아니라 <b>행 전체</b> 예산이다</li>
 * </ul>
 */
public interface FirebaseMessagingClient {

    /**
     * 같은 메시지를 여러 token 에 보낸다.
     *
     * @return 입력 token 과 같은 길이·같은 순서의 결과
     * @throws watoo.grd.nextroute.application.journey.exception.NotificationSendException
     *         호출 자체가 실패했을 때. 예산 초과는 일시 실패다
     */
    List<PushResult> send(PushMessage message, List<String> tokens);

    /**
     * token 하나에 대한 결과.
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
