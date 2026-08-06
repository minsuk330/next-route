package watoo.grd.nextroute.domain.push.entity;

/**
 * token 을 발급한 push 중계자.
 *
 * <p>지금은 FCM 하나뿐이다. iOS 도 FCM token 을 쓰고, 실제 배달만 그 뒤에서 APNs 를 거친다 —
 * 그래서 {@code APNS} 라는 값은 없다.
 *
 * <p>값이 하나인데 칼럼을 두는 이유. 이 token 문자열이 누구의 주소 체계인지는 발송 코드가
 * 알아야 하는 정보이고, 나중에 중계자가 늘면 그때 명시적으로 ALTER 하게 만든다.
 *
 * <p>값 목록은 DB 가 소유한다({@code user_push_token_provider_check}, V39).
 */
public enum PushProvider {
    FCM
}
