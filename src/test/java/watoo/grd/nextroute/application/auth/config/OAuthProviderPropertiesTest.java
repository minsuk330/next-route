package watoo.grd.nextroute.application.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 허용 목록이 비면 기동을 막는지 확인한다.
 *
 * <p>빈 목록을 "전부 통과"로 다루는 구현은 검증이 없는 것과 같다. 설정을 빠뜨린 채로 뜨면
 * 아무 앱이 발급받은 토큰이나 통과하므로, 뜨기 전에 실패해야 한다.
 */
class OAuthProviderPropertiesTest {

    private OAuthProviderProperties properties(List<String> appleAudiences) {
        OAuthProviderProperties props = new OAuthProviderProperties();
        props.getApple().setJwkSetUri("https://appleid.apple.com/auth/keys");
        props.getApple().setIssuer("https://appleid.apple.com");
        props.getApple().setAudiences(appleAudiences);
        props.getKakao().setUserInfoUri("https://kapi.kakao.com/v2/user/me");
        return props;
    }

    @Test
    @DisplayName("값이 채워져 있으면 통과한다")
    void validConfig_passes() {
        assertThatCode(() -> properties(List.of("com.golbob.nextroute")).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("애플 허용 앱 목록이 비면 기동을 막는다")
    void emptyAppleAudiences_failsStartup() {
        assertThatThrownBy(() -> properties(List.of()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oauth.apple.audiences");
    }

    @Test
    @DisplayName("공백만 있는 목록도 빈 것으로 본다")
    void blankOnlyAudiences_failsStartup() {
        assertThatThrownBy(() -> properties(List.of("  ", "")).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("null 목록도 빈 것으로 본다")
    void nullAudiences_failsStartup() {
        assertThatThrownBy(() -> properties(null).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("카카오 조회 주소가 비면 기동을 막는다")
    void blankKakaoUri_failsStartup() {
        OAuthProviderProperties props = properties(List.of("com.golbob.nextroute"));
        props.getKakao().setUserInfoUri("  ");

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oauth.kakao.user-info-uri");
    }
}
