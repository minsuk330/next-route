package watoo.grd.nextroute.application.auth.port.in;

import watoo.grd.nextroute.application.auth.dto.MeResult;
import watoo.grd.nextroute.application.auth.dto.TokenResult;

/**
 * 로그인한 뒤를 다룬다. 앱(애플·카카오) 사용자용이다.
 *
 * <p>토스 미니앱은 {@link TossLoginUseCase} 가 계속 담당한다. 미니앱은 별개 계정 체계이고,
 * 그쪽 로그아웃은 세션 종료가 아니라 토스 연결 끊기다.
 */
public interface SessionUseCase {

    /** refresh 토큰으로 access/refresh 재발급. 로그아웃된 토큰이면 거부한다. */
    TokenResult reissue(String refreshToken);

    /** 로그아웃. 이 사용자에게 발급된 모든 토큰이 즉시 무효가 된다. */
    void logout(long userId);

    /** 내 정보. */
    MeResult me(long userId);
}
