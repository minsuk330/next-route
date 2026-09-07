package watoo.grd.nextroute.application.route.routing;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.config.RoutingProviderProperties;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * provider 를 고르고 해당 {@link RoutingPort} 를 돌려준다. 선택은 상위 서비스가 하며 adapter 는
 * 선택된 provider 만 호출한다. OTP 는 운영 설정({@code routing.provider.otp-enabled})으로만 열린다 —
 * 일반 요청 파라미터로는 미승인 OTP 를 열 수 없다(P2-B).
 */
@Component
public class RoutingProviderSelector {

    private final RoutingProviderProperties properties;
    private final Map<RoutingProvider, RoutingPort> ports = new EnumMap<>(RoutingProvider.class);

    public RoutingProviderSelector(RoutingProviderProperties properties, List<RoutingPort> availablePorts) {
        this.properties = properties;
        for (RoutingPort port : availablePorts) {
            ports.put(port.provider(), port);
        }
    }

    /**
     * 사용할 provider 를 정한다.
     *
     * @param otpRequestedByInternal 내부/승인 경로가 OTP 를 청했는지. 일반 공개 요청은 항상 false 여야 한다
     * @return otp-enabled 이고 내부에서 청했을 때만 OTP, 그 밖에는 기본 provider
     */
    public RoutingProvider select(boolean otpRequestedByInternal) {
        if (properties.isOtpEnabled() && otpRequestedByInternal) {
            return RoutingProvider.OTP;
        }
        return properties.getDefaultProvider();
    }

    /** 선택된 provider 의 port. 없으면 구성 오류. */
    public RoutingPort portFor(RoutingProvider provider) {
        RoutingPort port = ports.get(provider);
        if (port == null) {
            throw new IllegalStateException("RoutingPort 미등록: " + provider);
        }
        return port;
    }
}
