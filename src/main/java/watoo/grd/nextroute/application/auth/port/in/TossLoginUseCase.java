package watoo.grd.nextroute.application.auth.port.in;

import watoo.grd.nextroute.application.auth.dto.LoginResult;
import watoo.grd.nextroute.application.auth.dto.TokenResult;

public interface TossLoginUseCase {

    /**
     * 인가코드로 로그인 처리: 토큰 교환 → 사용자정보 조회 → User upsert → 자체 JWT 발급.
     * 신원은 토스 사용자 식별키(tossUserKey)로만 식별한다.
     */
    LoginResult login(String authorizationCode, String referrer);

    /** 자체 refresh JWT로 access/refresh 재발급. */
    TokenResult reissue(String refreshToken);

    /** 로그아웃: 토스 로그인 연결 끊기 + 보관 토큰 삭제. */
    void logout(long userId);

    /** 토스 연결끊기 콜백 처리(idempotent). */
    void handleUnlinkCallback(long userKey, String referrer);
}
