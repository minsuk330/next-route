package watoo.grd.nextroute.application.auth.exception;

/**
 * 세션이 더 이상 유효하지 않다. 재발급 거부와 사라진 사용자 모두 이 예외로 모은다.
 *
 * <p>왜 거부됐는지(만료·타입 불일치·로그아웃됨·계정 없음)는 응답에 싣지 않는다.
 * 앱이 할 일은 어느 쪽이든 똑같이 재로그인이고, 구분해 주면 토큰을 넣어 보며
 * 유효한 사용자 번호를 찾아낼 수 있다.
 */
public class SessionException extends RuntimeException {

    public SessionException(String message) {
        super(message);
    }

    public SessionException(String message, Throwable cause) {
        super(message, cause);
    }
}
