package watoo.grd.nextroute.application.route.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 환승 도착예측 기능 토글.
 * enabled=false면 fan-out 전체 차단 (REALTIME 포함).
 * ML은 ml.predictor.enabled로 별도 제어 가능.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "route.transfer-arrival")
public class TransferArrivalProperties {
    private boolean enabled = false;
}
