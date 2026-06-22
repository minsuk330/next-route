package watoo.grd.nextroute.application.auth.port.out;

import java.util.List;

/**
 * 토스 로그인 외부 API 아웃바운드 포트.
 * 토큰 교환/재발급, 사용자정보 조회(복호화 포함), 로그인 끊기를 담당한다.
 */
public interface TossLoginPort {

    /** 인가코드로 AccessToken/RefreshToken 발급(mTLS). */
    TossToken generateToken(String authorizationCode, String referrer);

    /** RefreshToken으로 토큰 재발급. */
    TossToken refreshToken(String refreshToken);

    /** AccessToken으로 사용자정보 조회. 암호화 필드는 복호화된 값으로 반환. */
    TossUserInfo getUserInfo(String accessToken);

    /** AccessToken으로 로그인 연결 끊기. */
    void unlinkByAccessToken(String accessToken);

    /** userKey로 로그인 연결 끊기. */
    void unlinkByUserKey(long userKey);

    record TossToken(
            String tokenType,
            String accessToken,
            String refreshToken,
            long expiresIn,
            String scope
    ) { }

    record TossUserInfo(
            long userKey,
            String scope,
            List<String> agreedTerms,
            String name,
            String phone,
            String birthday,
            String ci,
            String gender,
            String nationality,
            String email
    ) { }
}
