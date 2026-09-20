package watoo.grd.nextroute.application.route.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본값 계약 가드(P7). 프로덕션은 env 로 OTP 를 주입하고 있지만, 그 env 가 빠져도 코드가
 * 조용히 ODSAY 로 돌아가면 안 된다 — 전환 사실은 코드에 적혀 있어야 한다.
 */
class RoutingProviderPropertiesTest {

    @Test
    @DisplayName("설정 없이도 기본은 OTP, 승인 게이트는 열려 있다")
    void defaultsToOtp() {
        RoutingProviderProperties props = new RoutingProviderProperties();

        assertThat(props.getDefaultProvider()).isEqualTo(RoutingProvider.OTP);
        assertThat(props.isOtpEnabled()).isTrue();
    }

    @Test
    @DisplayName("ODSAY 롤백은 값 주입으로만 — 기본값이 아니다")
    void rollbackIsExplicit() {
        RoutingProviderProperties props = new RoutingProviderProperties();
        props.setDefaultProvider(RoutingProvider.ODSAY);

        assertThat(props.getDefaultProvider()).isEqualTo(RoutingProvider.ODSAY);
    }
}
