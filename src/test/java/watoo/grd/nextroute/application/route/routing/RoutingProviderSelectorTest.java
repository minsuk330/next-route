package watoo.grd.nextroute.application.route.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.config.RoutingProviderProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingProviderSelectorTest {

    private RoutingPort stub(RoutingProvider p) {
        return new RoutingPort() {
            @Override public RoutingOutcome search(RoutingQuery q, RoutingDeadline d) { return null; }
            @Override public RoutingProvider provider() { return p; }
        };
    }

    /** 기본값에 기대지 않고 두 값을 매번 명시한다 — 기본값이 바뀌어도 이 테스트가 뜻을 잃지 않게. */
    private RoutingProviderSelector selector(RoutingProvider defaultProvider, boolean otpEnabled) {
        RoutingProviderProperties props = new RoutingProviderProperties();
        props.setDefaultProvider(defaultProvider);
        props.setOtpEnabled(otpEnabled);
        return new RoutingProviderSelector(props,
                List.of(stub(RoutingProvider.ODSAY), stub(RoutingProvider.OTP)));
    }

    @Test
    @DisplayName("default-provider=OTP + enabled 면 일반 요청도 OTP (P7 전환 상태)")
    void otpIsDefaultAfterSwitchover() {
        assertThat(selector(RoutingProvider.OTP, true).select(false)).isEqualTo(RoutingProvider.OTP);
        assertThat(selector(RoutingProvider.OTP, true).select(true)).isEqualTo(RoutingProvider.OTP);
    }

    @Test
    @DisplayName("otp-enabled=false 면 default 가 OTP 여도 ODSAY (승인 게이트, 오설정 방어)")
    void approvalGateOverridesDefault() {
        assertThat(selector(RoutingProvider.OTP, false).select(false)).isEqualTo(RoutingProvider.ODSAY);
        assertThat(selector(RoutingProvider.OTP, false).select(true)).isEqualTo(RoutingProvider.ODSAY);
    }

    @Test
    @DisplayName("default-provider=ODSAY 로 되돌리면 일반 요청은 ODSAY (롤백 통로)")
    void rollbackToOdsay() {
        assertThat(selector(RoutingProvider.ODSAY, true).select(false)).isEqualTo(RoutingProvider.ODSAY);
        // 내부/승인 경로가 청하면 여전히 OTP — 롤백은 기본값만 되돌린다.
        assertThat(selector(RoutingProvider.ODSAY, true).select(true)).isEqualTo(RoutingProvider.OTP);
    }

    @Test
    @DisplayName("portFor 는 provider 별 port, 미등록은 오류")
    void portLookup() {
        RoutingProviderSelector s = selector(RoutingProvider.OTP, true);
        assertThat(s.portFor(RoutingProvider.OTP).provider()).isEqualTo(RoutingProvider.OTP);
        RoutingProviderSelector empty = new RoutingProviderSelector(new RoutingProviderProperties(), List.of());
        assertThatThrownBy(() -> empty.portFor(RoutingProvider.OTP))
                .isInstanceOf(IllegalStateException.class);
    }
}
