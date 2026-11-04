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

    /**
     * 예측 결과 캐시 TTL. 0 이하면 캐시를 완전히 우회한다.
     *
     * <p>신선도는 TTL 이 아니라 <b>키</b>가 책임진다. 키가 feature 값 전체에서 나오므로 버스 위치
     * 스냅샷이 갱신되면 키가 저절로 바뀐다. 그래서 이 값은 "옛 세대의 답을 얼마나 오래 들고
     * 있을지"만 정한다 — 상류 위치 캐시(15초)보다 조금 길게 잡아 세대 경계의 miss 를 줄인다.
     */
    private long cacheTtlSeconds = 30;

    /**
     * rt_* 피처용 노선별 관측 이력. false 면 이력을 쌓지도 요청에 싣지도 않는다 — serving 은
     * 그 피처를 NaN 으로 두고 예측한다(학습 때 본 경우).
     */
    private boolean historyEnabled = true;

    /** 이력 보존 시간(분). serving 이 쓰는 창(realtime_history_minutes, 90분)보다 길게 둔다. */
    private int historyMinutes = 100;
}
