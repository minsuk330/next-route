package watoo.grd.nextroute.infrastructure.adapter.out.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.auth.config.OAuthProviderProperties;
import watoo.grd.nextroute.application.auth.dto.OAuthCredential;
import watoo.grd.nextroute.application.auth.dto.VerifiedIdentity;
import watoo.grd.nextroute.application.auth.exception.OAuthVerificationException;
import watoo.grd.nextroute.application.auth.port.out.OAuthChallengePort;
import watoo.grd.nextroute.application.auth.port.out.OAuthIdentityVerifier;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 애플 로그인 토큰 검증.
 *
 * <p>서명·발급처·대상앱·유효기간은 {@link JwtDecoder} 가 본다(열쇠 교체 추적과 캐시 포함).
 * 이 클래스는 그 위에 <b>일회용 값 소비</b>를 얹는다.
 *
 * <p>이름은 토큰에 없다. 애플은 최초 인증 때 앱에만 한 번 건네주므로 앱이 따로 넘겨야 한다.
 */
@Slf4j
@Component
public class AppleIdentityVerifier implements OAuthIdentityVerifier {

    private final JwtDecoder decoder;
    private final OAuthChallengePort challengePort;

    public AppleIdentityVerifier(JwkDecoderFactory decoderFactory,
                                 OAuthProviderProperties properties,
                                 OAuthChallengePort challengePort) {
        OAuthProviderProperties.Apple apple = properties.getApple();
        this.decoder = decoderFactory.decoder(
                apple.getJwkSetUri(), apple.getIssuer(), apple.getAudiences());
        this.challengePort = challengePort;
    }

    @Override
    public AuthProvider provider() {
        return AuthProvider.APPLE;
    }

    @Override
    public VerifiedIdentity verify(OAuthCredential credential) {
        Jwt jwt = decode(credential.credential());
        consumeNonce(credential.nonce(), jwt.getClaimAsString("nonce"));

        return new VerifiedIdentity(
                AuthProvider.APPLE,
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                // 애플은 이름을 토큰에 담지 않는다. 앱이 넘긴 값을 그대로 쓴다.
                credential.displayName());
    }

    private Jwt decode(String token) {
        if (token == null || token.isBlank()) {
            throw new OAuthVerificationException("애플 토큰이 비어 있다");
        }
        try {
            return decoder.decode(token);
        } catch (JwtException e) {
            // 어느 항목이 틀렸는지는 응답에 싣지 않는다. 공격자에게 힌트가 된다.
            log.debug("[Apple] 토큰 검증 실패: {}", e.getMessage());
            throw new OAuthVerificationException("애플 토큰 검증 실패", e);
        }
    }

    /**
     * 서버가 발급한 값이 맞는지 확인하면서 소비한다.
     *
     * <p>두 검사가 <b>모두</b> 필요하다.
     * <ul>
     *   <li>토큰 안의 값과 요청의 값이 같은가 — 이 애플 토큰이 그 challenge 로 발급됐는가</li>
     *   <li>저장소에 있었는가 — 서버가 발급한 값이고 처음 쓰는가</li>
     * </ul>
     * 앞의 것만 하면 토큰과 nonce 를 함께 훔친 공격자가 통과한다(둘이 당연히 일치한다).
     * 뒤의 것만 하면 공격자가 자기 challenge 를 받아 남의 토큰을 붙여 보낼 수 있다.
     *
     * <p><b>토큰에는 원본이 아니라 SHA-256 해시가 담긴다.</b> 애플 로그인의 표준 절차가
     * 앱이 해시를 넘기도록 돼 있기 때문이다. 서버는 원본을 발급하고 원본을 돌려받되,
     * 비교는 해시로 한다. 이러면 규약을 서버가 통제하고 앱은 해시만 계산하면 된다.
     */
    private void consumeNonce(String requestNonce, String tokenNonce) {
        if (requestNonce == null || requestNonce.isBlank()) {
            throw new OAuthVerificationException("애플 로그인에는 nonce 가 필요하다");
        }
        if (tokenNonce == null || !constantTimeEquals(sha256Hex(requestNonce), tokenNonce)) {
            throw new OAuthVerificationException("nonce 가 토큰과 일치하지 않는다");
        }
        if (!challengePort.consume(requestNonce)) {
            throw new OAuthVerificationException("이미 사용했거나 만료된 nonce 다");
        }
    }

    /** 앱이 애플 SDK 에 넘기는 값과 같은 방식으로 계산한다. 소문자 16진수. */
    static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }

    /** 길이에 관계없이 같은 시간이 걸리게 비교한다. 조기 반환은 값을 한 글자씩 알아내는 통로가 된다. */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
