package watoo.grd.nextroute.domain.push.entity;

/**
 * token 이 어느 쪽 APNs 를 향하는지.
 *
 * <p>iOS 개발 빌드는 sandbox token 을 받는다. 그것을 운영 설정으로 보내면 배달되지 않으므로
 * 구분해 둔다. Android 는 구분이 없어 항상 {@link #PRODUCTION} 이다.
 *
 * <p>값 목록은 DB 가 소유한다({@code user_push_token_environment_check}, V39).
 */
public enum PushEnvironment {
    PRODUCTION,
    /** APNs sandbox. iOS 개발·TestFlight 빌드. */
    DEVELOPMENT
}
