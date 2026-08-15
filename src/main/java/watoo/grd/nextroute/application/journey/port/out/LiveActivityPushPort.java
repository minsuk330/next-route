package watoo.grd.nextroute.application.journey.port.out;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Live Activity 원격 갱신의 유일한 창구. SDK 타입을 이 인터페이스 밖으로 내보내지 않는다.
 *
 * <p>여정 알림({@link FirebaseMessagingClient})과 창구를 나눈 이유는 <b>보내는 것이 다르기</b>
 * 때문이다. 그쪽은 제목·본문이 있는 알림이고 이쪽은 알림이 아니라 <b>위젯의 상태</b>다 —
 * 소리도 배지도 없고, 대신 {@code aps.event}·{@code aps.timestamp}·{@code content-state} 라는
 * 전용 키가 붙는다. 한 창구에 넣으면 어느 쪽에도 안 맞는 인자 목록이 된다.
 */
public interface LiveActivityPushPort {

    /**
     * 한 Activity 에 한 번 보낸다.
     *
     * @param deviceToken   FCM registration token. 어느 <b>기기</b>인지
     * @param activityToken ActivityKit push token. 그 기기 안의 어느 <b>Activity</b> 인지
     * @param event         {@code update} 또는 {@code end}
     * @param apsTimestamp  epoch <b>초</b>. Activity 별로 단조 증가해야 한다
     * @param contentState  {@code {name, props}} 두 겹. {@code props} 는 JSON 문자열이다
     */
    PushResult send(String deviceToken, String activityToken, String event,
                    long apsTimestamp, JsonNode contentState);

    /**
     * 발송 결과.
     *
     * @param errorCode provider 가 준 오류 코드 이름. 성공이면 null
     */
    record PushResult(boolean success, String errorCode) {

        public static PushResult ok() {
            return new PushResult(true, null);
        }

        public static PushResult failed(String errorCode) {
            return new PushResult(false, errorCode);
        }

        /**
         * 그 주소가 죽었다는 뜻인지.
         *
         * <p>{@code INVALID_ARGUMENT}·{@code SENDER_ID_MISMATCH} 는 <b>여기 넣지 않는다</b> —
         * payload·자격증명 문제로도 나는 코드라, 설정 실수 한 번이 주소록을 통째로 지운다
         * (V39 규칙과 같다).
         */
        public boolean isTokenDead() {
            return "UNREGISTERED".equals(errorCode);
        }
    }
}
