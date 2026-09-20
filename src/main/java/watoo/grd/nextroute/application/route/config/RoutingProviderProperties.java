package watoo.grd.nextroute.application.route.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;

/**
 * provider 선택 정책. 기본은 OTP(P7 전환 완료) — 수도권 검색·재탐색은 OTP graph 로 답한다.
 * ODSAY 는 지우지 않고 남겨 둔 <b>롤백 통로</b>다. env 로 {@code routing.provider.default-provider=ODSAY}
 * 를 주면 즉시 되돌아간다.
 *
 * <p>{@code otp-enabled} 는 전환 스위치가 아니라 <b>승인 게이트</b>다(P2-B). 일반 요청 파라미터로
 * 미승인 OTP 범위를 열 수 없게 막는 자리이며, false 면 default-provider 가 OTP 라도 ODSAY 로 내려간다.
 */
@Component
@ConfigurationProperties(prefix = "routing.provider")
public class RoutingProviderProperties {

    /** 최초 검색·재탐색의 기본 provider. P7 부터 OTP. ODSAY 로 되돌리려면 env 로 명시한다. */
    private RoutingProvider defaultProvider = RoutingProvider.OTP;
    /** OTP 를 선택할 수 있는지(내부/승인 스위치). false 면 요청이 OTP 를 청해도 무시하고 기본으로. */
    private boolean otpEnabled = true;

    public RoutingProvider getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(RoutingProvider defaultProvider) { this.defaultProvider = defaultProvider; }
    public boolean isOtpEnabled() { return otpEnabled; }
    public void setOtpEnabled(boolean otpEnabled) { this.otpEnabled = otpEnabled; }
}
