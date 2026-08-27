package watoo.grd.nextroute.infrastructure.adapter.out.auth;

import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.auth.config.OAuthProviderProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * 애플 토큰 엔드포인트(/auth/token, /auth/revoke)에 낼 {@code client_secret} 을 만든다.
 *
 * <p>애플은 client_secret 을 고정 문자열이 아니라 <b>우리가 .p8 개인키로 서명한 ES256 JWT</b> 로
 * 요구한다. 유효기간을 짧게(5분) 두고 호출마다 새로 만든다 — 저빈도라 캐시할 이유가 없고, 짧은
 * 만료가 유출 시 피해를 줄인다.
 *
 * <ul>
 *   <li>iss = teamId, sub = clientId(번들 ID), aud = https://appleid.apple.com</li>
 *   <li>header: kid = keyId, alg = ES256</li>
 * </ul>
 */
@Component
public class AppleClientSecretJwtBuilder {

    private static final String APPLE_AUD = "https://appleid.apple.com";
    private static final long TTL_SECONDS = 300; // 5분

    private final OAuthProviderProperties.Apple apple;

    public AppleClientSecretJwtBuilder(OAuthProviderProperties properties) {
        this.apple = properties.getApple();
    }

    /** 서명된 client_secret JWT 문자열을 반환한다. */
    public String build() {
        ECPrivateKey privateKey = loadPrivateKey();
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId(apple.getKeyId()).and()
                .issuer(apple.getTeamId())
                .subject(apple.getClientId())
                .audience().add(APPLE_AUD).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(TTL_SECONDS)))
                .signWith(privateKey, Jwts.SIG.ES256)
                .compact();
    }

    /** .p8(PEM, PKCS#8 EC) 파일을 읽어 EC 개인키로 파싱한다. */
    private ECPrivateKey loadPrivateKey() {
        String path = apple.getPrivateKeyPath();
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(
                    "oauth.apple.private-key-path 가 비어 있다. .p8 경로를 설정해야 client_secret 을 만들 수 있다.");
        }
        try {
            String pem = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            String base64 = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            PrivateKey key = KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
            return (ECPrivateKey) key;
        } catch (Exception e) {
            throw new IllegalStateException("애플 .p8 개인키 로드에 실패했다: " + path, e);
        }
    }
}
