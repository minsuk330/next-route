package watoo.grd.nextroute.application.auth.dto;

/** 자체 JWT 재발급 결과. */
public record TokenResult(
        String accessToken,
        String refreshToken
) { }
