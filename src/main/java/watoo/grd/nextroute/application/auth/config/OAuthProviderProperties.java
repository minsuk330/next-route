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
        requireText(kakao.getTokenInfoUri(), "oauth.kakao.token-info-uri");
        requireText(kakao.getUserInfoUri(), "oauth.kakao.user-info-uri");
        requireAppId(kakao.getAppId());
    }

    private static void requireNonEmpty(List<String> values, String key) {
        if (values == null || values.isEmpty() || values.stream().allMatch(String::isBlank)) {
            throw new IllegalStateException(
                    key + " 가 비어 있다. 빈 허용 목록은 '전부 통과'로 동작하므로 기동을 막는다. "
                            + "env/oauth.env 를 채워라(예시는 env/oauth.env.example).");
        }
    }

    /**
     * 카카오 앱 식별자가 없으면 기동을 막는다.
     *
     * <p>없다고 검증을 건너뛰면 다른 앱이 발급받은 토큰이 그대로 통과한다.
     * 애플의 빈 허용 목록과 같은 함정이다.
     */
    private static void requireAppId(Long appId) {
        if (appId == null || appId <= 0) {
            throw new IllegalStateException(
                    "oauth.kakao.app-id 가 없다. 없으면 다른 앱이 발급받은 토큰도 통과하므로 기동을 막는다. "
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

        /**
         * 토큰 교환·revoke 의 client_id. 번들 ID(=주 audience)와 같다.
         *
         * <p>비어 있으면 부팅은 되고 교환·revoke 실호출만 실패한다(mTLS keystore 선례).
         * .p8 자격을 아직 발급받지 않아도 앱이 뜨게 하기 위함이다.
         */
        private String clientId;
        /** 애플 개발자 팀 식별자(client_secret JWT 의 iss). */
        private String teamId;
        /** .p8 개인키의 Key ID(client_secret JWT header 의 kid). */
        private String keyId;
        /** .p8(PKCS#8 EC 개인키) 파일 경로. 컨테이너 볼륨 마운트 경로. */
        private String privateKeyPath;
        /** 애플 토큰 엔드포인트의 기준 주소. /auth/token·/auth/revoke 가 이 아래 붙는다. */
        private String tokenBaseUrl = "https://appleid.apple.com";
    }

    @Getter
    @Setter
    public static class Kakao {
        /**
         * 토큰이 <b>우리 앱을 위해</b> 발급됐는지 확인할 주소.
         *
         * <p>카카오 토큰은 불투명 문자열이라 애플처럼 안을 열어 대상 앱을 볼 수 없다.
         * 그래서 별도 조회로 app_id 를 받아 대조한다. 애플의 aud 검사에 해당한다.
         */
        private String tokenInfoUri;

        /** 프로필(이메일·닉네임) 조회 주소. */
        private String userInfoUri;

        /**
         * 우리 카카오 앱의 식별자. 토큰의 app_id 가 이 값과 다르면 거부한다.
         *
         * <p>비워 두면 검증을 건너뛰게 되므로 기동을 막는다.
         */
        private Long appId;
    }

    @Getter
    @Setter
    public static class Challenge {
        /** 애플 일회용 값 보관 시간(초). */
        private long ttlSeconds = 300;
    }
}
