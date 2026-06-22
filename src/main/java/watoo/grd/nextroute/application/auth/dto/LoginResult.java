package watoo.grd.nextroute.application.auth.dto;

/** 토스 로그인 성공 결과. accessToken/refreshToken은 우리 서버가 발급한 자체 JWT. */
public record LoginResult(
        String accessToken,
        String refreshToken,
        long userId,
        long tossUserKey,
        String name
) { }
