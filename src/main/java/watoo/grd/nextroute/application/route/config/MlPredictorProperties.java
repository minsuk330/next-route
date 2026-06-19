package watoo.grd.nextroute.application.route.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ML 도착예측 serving(FastAPI) 호출 설정.
 * enabled=false면 ML 호출 자체를 하지 않는다(REALTIME 분기는 별도 toggle로 제어 — PR3).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ml.predictor")
public class MlPredictorProperties {
    private String baseUrl = "http://localhost:8001";
    private int timeoutMs = 3000;
    private boolean enabled = false;
}
