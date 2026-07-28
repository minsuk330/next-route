package watoo.grd.nextroute.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.auth.config.JwtProperties;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** 자체 JWT(access/refresh) 발급·검증. HMAC-SHA256. subject=userId, claim type=access|refresh. */
@Component
@RequiredArgsConstructor
public class JwtProvider {

    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final JwtProperties properties;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(long userId) {
        return create(userId, TYPE_ACCESS, properties.getAccessTtlSeconds());
    }

    public String createRefreshToken(long userId) {
        return create(userId, TYPE_REFRESH, properties.getRefreshTtlSeconds());
    }

    private String create(long userId, String type, long ttlSeconds) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ttlSeconds * 1000);
        return Jwts.builder()
                .issuer(properties.getIssuer())
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, type)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key())
                .compact();
    }

    /**
     * access 토큰인지 검증하고 userId 반환. 만료/위조/타입 불일치 시 JwtException.
     *
     * <p>타입 검사가 없으면 refresh 토큰을 {@code Authorization: Bearer} 로 보내
     * 보호된 API 를 호출할 수 있다. access 는 1시간, refresh 는 14일이므로
     * 짧은 access 수명이 무의미해진다. API 인증 경로는 반드시 이 메서드만 쓴다.
     */
    public long parseAccessUserId(String token) {
        return parseTyped(token, TYPE_ACCESS, "access 토큰이 아님");
    }

    /** refresh 토큰인지 검증하고 userId 반환. */
    public long parseRefreshUserId(String token) {
        return parseTyped(token, TYPE_REFRESH, "refresh 토큰이 아님");
    }

    private long parseTyped(String token, String expectedType, String message) {
        Claims claims = parse(token);
        if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException(message);
        }
        return Long.parseLong(claims.getSubject());
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .requireIssuer(properties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
