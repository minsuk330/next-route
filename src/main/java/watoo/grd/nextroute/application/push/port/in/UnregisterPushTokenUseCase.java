package watoo.grd.nextroute.application.push.port.in;

/**
 * 기기 push token 해제.
 *
 * <p>로그아웃이 이것을 부른다. JWT 를 버리는 것만으로는 token 이 떨어지지 않는다 — 두 토큰의
 * 수명이 완전히 따로 놀기 때문이다. 안 부르면 로그아웃한 폰에 계속 알림이 간다.
 */
public interface UnregisterPushTokenUseCase {

    void unregister(Long userId, String token);
}
