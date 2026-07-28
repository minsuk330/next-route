package watoo.grd.nextroute.application.auth.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 앱 OAuth provider 설정. 값은 {@code env/oauth.env} 로 주입한다.
 *
 * <p>허용 앱 목록이 비어 있으면 기동을 막는다. 빈 목록을 "전부 통과"로 다루는 구현은
 * 검증이 없는 것과 같기 때문이다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "oauth")
public class OAuthProviderProperties {

    private Apple apple = new Apple();
    private Kakao kakao = new Kakao();
    private Challenge challenge = new Challenge();

    @PostConstruct
    public void validate() {
        requireNonEmpty(apple.getAudiences(), "oauth.apple.audiences");
        requireText(apple.getJwkSetUri(), "oauth.apple.jwk-set-uri");
        requireText(apple.getIssuer(), "oauth.apple.issuer");
        requireText(kakao.getUserInfoUri(), "oauth.kakao.user-info-uri");
    }

    private static void requireNonEmpty(List<String> values, String key) {
        if (values == null || values.isEmpty() || values.stream().allMatch(String::isBlank)) {
            throw new IllegalStateException(
                    key + " 가 비어 있다. 빈 허용 목록은 '전부 통과'로 동작하므로 기동을 막는다. "
                            + "env/oauth.env 를 채워라(예시는 env/oauth.env.example).");
        }
    }

    private static void requireText(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " 가 비어 있다. env/oauth.env 를 채워라.");
        }
    }

    @Getter
    @Setter
    public static class Apple {
        /** 애플이 공개한 서명 검증 열쇠 목록 주소. */
        private String jwkSetUri;
        /** 토큰 발급처. 이 값과 다르면 거부한다. */
        private String issuer;
        /** 허용할 대상 앱 식별자. iOS Bundle ID, (Android 지원 시) Service ID. */
        private List<String> audiences = List.of();
    }

    @Getter
    @Setter
    public static class Kakao {
        /** 서버가 직접 호출해 토큰 유효성을 확인할 주소. */
        private String userInfoUri;
    }

    @Getter
    @Setter
    public static class Challenge {
        /** 애플 일회용 값 보관 시간(초). */
        private long ttlSeconds = 300;
    }
}
