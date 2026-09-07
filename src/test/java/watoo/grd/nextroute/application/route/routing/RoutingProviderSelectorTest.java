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

    private RoutingProviderSelector selector(boolean otpEnabled) {
        RoutingProviderProperties props = new RoutingProviderProperties();
        props.setOtpEnabled(otpEnabled);
        return new RoutingProviderSelector(props,
                List.of(stub(RoutingProvider.ODSAY), stub(RoutingProvider.OTP)));
    }

    @Test
    @DisplayName("기본은 ODSAY. otp-enabled 여도 내부요청 아니면 ODSAY")
    void defaultsToOdsay() {
        assertThat(selector(false).select(false)).isEqualTo(RoutingProvider.ODSAY);
        assertThat(selector(false).select(true)).isEqualTo(RoutingProvider.ODSAY);   // enabled=false 라 무시
        assertThat(selector(true).select(false)).isEqualTo(RoutingProvider.ODSAY);   // 내부요청 아님
    }

    @Test
    @DisplayName("otp-enabled + 내부요청일 때만 OTP")
    void otpOnlyWhenEnabledAndInternal() {
        assertThat(selector(true).select(true)).isEqualTo(RoutingProvider.OTP);
    }

    @Test
    @DisplayName("portFor 는 provider 별 port, 미등록은 오류")
    void portLookup() {
        RoutingProviderSelector s = selector(true);
        assertThat(s.portFor(RoutingProvider.OTP).provider()).isEqualTo(RoutingProvider.OTP);
        RoutingProviderSelector empty = new RoutingProviderSelector(new RoutingProviderProperties(), List.of());
        assertThatThrownBy(() -> empty.portFor(RoutingProvider.OTP))
                .isInstanceOf(IllegalStateException.class);
    }
}
