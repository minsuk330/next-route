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

/**
 * 자체 JWT(access/refresh) 발급·검증. HMAC-SHA256.
 * subject=userId, claim type=access|refresh, claim gen=토큰 세대 번호.
 *
 * <p><b>이 클래스는 저장소를 보지 않는다.</b> 서명·만료·타입처럼 토큰만 보면 알 수 있는 것까지만
 * 판단하고, 세대 번호가 지금도 유효한지는 {@link JwtAuthFilter} 가 대조한다. 순수 코덱으로 남겨야
 * 슬라이스 테스트가 이 빈을 통째로 가짜로 바꿔 쓰는 관례를 유지할 수 있다.
 */
@Component
@RequiredArgsConstructor
public class JwtProvider {

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_GENERATION = "gen";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final JwtProperties properties;

    /**
     * 토큰이 주장하는 신원.
     *
     * @param generation 발급 시점의 사용자 세대 번호. 지금도 유효한지는 대조해 봐야 안다
     */
    public record TokenPrincipal(long userId, int generation) { }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(long userId, int generation) {
        return create(userId, generation, TYPE_ACCESS, properties.getAccessTtlSeconds());
    }

    public String createRefreshToken(long userId, int generation) {
        return create(userId, generation, TYPE_REFRESH, properties.getRefreshTtlSeconds());
    }

    private String create(long userId, int generation, String type, long ttlSeconds) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ttlSeconds * 1000);
        return Jwts.builder()
                .issuer(properties.getIssuer())
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, type)
                .claim(CLAIM_GENERATION, generation)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key())
                .compact();
    }

    /**
     * access 토큰인지 검증하고 신원을 반환. 만료/위조/타입 불일치 시 JwtException.
     *
     * <p>타입 검사가 없으면 refresh 토큰을 {@code Authorization: Bearer} 로 보내
     * 보호된 API 를 호출할 수 있다. access 는 1시간, refresh 는 14일이므로
     * 짧은 access 수명이 무의미해진다. API 인증 경로는 반드시 이 메서드만 쓴다.
     */
    public TokenPrincipal parseAccessPrincipal(String token) {
        return parseTyped(token, TYPE_ACCESS, "access 토큰이 아님");
    }

    /** refresh 토큰인지 검증하고 신원을 반환. */
    public TokenPrincipal parseRefreshPrincipal(String token) {
        return parseTyped(token, TYPE_REFRESH, "refresh 토큰이 아님");
    }

    private TokenPrincipal parseTyped(String token, String expectedType, String message) {
        Claims claims = parse(token);
        if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException(message);
        }
        return new TokenPrincipal(Long.parseLong(claims.getSubject()), generationOf(claims));
    }

    /**
     * 세대 번호가 없으면 0 으로 본다.
     *
     * <p>V34 이전에 발급된 토큰에는 이 값이 없다. 없다고 거부하면 배포 순간 로그인 중인 사용자가
     * 전부 튕긴다. 새 컬럼의 기본값도 0 이므로 "번호 없음"은 "아직 한 번도 로그아웃한 적 없음"과
     * 같은 뜻이 되어 그대로 통과하고, 그 사용자가 한 번 로그아웃하면 이후로는 정상 규칙을 탄다.
     */
    private static int generationOf(Claims claims) {
        Integer generation = claims.get(CLAIM_GENERATION, Integer.class);
        return generation == null ? 0 : generation;
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
