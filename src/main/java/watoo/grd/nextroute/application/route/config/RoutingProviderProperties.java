package watoo.grd.nextroute.application.route.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;

/**
 * provider 선택 정책. 기본은 ODSAY(전환은 P7). OTP 는 <b>내부/승인 범위</b>에서만 열린다 —
 * 일반 요청 파라미터로 미승인 OTP 범위를 열 수 없게 한다(P2-B).
 */
@Component
@ConfigurationProperties(prefix = "routing.provider")
public class RoutingProviderProperties {

    /** 최초 검색·재탐색의 기본 provider. */
    private RoutingProvider defaultProvider = RoutingProvider.ODSAY;
    /** OTP 를 선택할 수 있는지(내부/승인 스위치). false 면 요청이 OTP 를 청해도 무시하고 기본으로. */
    private boolean otpEnabled = false;

    public RoutingProvider getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(RoutingProvider defaultProvider) { this.defaultProvider = defaultProvider; }
    public boolean isOtpEnabled() { return otpEnabled; }
    public void setOtpEnabled(boolean otpEnabled) { this.otpEnabled = otpEnabled; }
}
