package watoo.grd.nextroute.infrastructure.adapter.out.auth;

import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * provider 별 토큰 검증기를 만든다.
 *
 * <p>서명 열쇠를 직접 관리하지 않는다. 회사가 열쇠를 주기적으로 갈아 끼우는데, 그 추적과 캐시를
 * 직접 짜면 그 자체가 사고 지점이 된다. Nimbus 가 담당한다.
 *
 * <p>이 라이브러리를 classpath 에 올려도 {@code SecurityConfig} 에서
 * {@code .oauth2ResourceServer(...)} 를 부르지 않으면 기존 인증 흐름은 변하지 않는다.
 */
@Component
public class JwkDecoderFactory {

    /**
     * @param audiences 허용할 대상 앱 식별자. 하나라도 겹치면 통과한다.
     *                  빈 목록이 넘어오면 만들지 않는다 — "전부 통과"가 되기 때문이다.
     */
    public JwtDecoder decoder(String jwkSetUri, String issuer, List<String> audiences) {
        List<String> allowed = audiences == null ? List.of()
                : audiences.stream().filter(a -> a != null && !a.isBlank()).toList();
        if (allowed.isEmpty()) {
            throw new IllegalStateException(
                    "허용 대상 앱 목록이 비어 있다: " + jwkSetUri + " — 빈 목록은 전부 통과를 뜻하므로 거부한다");
        }

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingValidator(
                JwtValidators.createDefaultWithIssuer(issuer),   // 발급처 + 유효기간
                new AudienceValidator(allowed)));
        return decoder;
    }

    /** 대상 앱 검사. 토큰의 aud 중 하나라도 허용 목록에 있어야 한다. */
    record AudienceValidator(List<String> allowed) implements OAuth2TokenValidator<Jwt> {

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            List<String> audience = token.getAudience();
            if (audience != null && audience.stream().anyMatch(allowed::contains)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new org.springframework.security.oauth2.core.OAuth2Error(
                            "invalid_token",
                            "허용되지 않은 대상 앱(" + JwtClaimNames.AUD + ")",
                            null));
        }
    }

    /** 여러 검사를 순서대로 적용하고 첫 실패를 돌려준다. */
    record DelegatingValidator(OAuth2TokenValidator<Jwt> first,
                               OAuth2TokenValidator<Jwt> second) implements OAuth2TokenValidator<Jwt> {

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            OAuth2TokenValidatorResult result = first.validate(token);
            return result.hasErrors() ? result : second.validate(token);
        }
    }
}
