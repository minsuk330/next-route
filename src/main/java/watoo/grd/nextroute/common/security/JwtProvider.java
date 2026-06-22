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

    /** 유효한 토큰이면 userId 반환. 만료/위조 시 JwtException. */
    public long parseUserId(String token) {
        return Long.parseLong(parse(token).getSubject());
    }

    /** refresh 토큰인지 검증하고 userId 반환. */
    public long parseRefreshUserId(String token) {
        Claims claims = parse(token);
        if (!TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException("refresh 토큰이 아님");
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
