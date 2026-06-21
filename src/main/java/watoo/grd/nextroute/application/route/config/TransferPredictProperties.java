package watoo.grd.nextroute.application.route.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 단일 환승 예측 API(/api/transfer/predict) 파라미터.
 * 전체 feature 게이트는 {@code route.transfer-arrival.enabled}, ML 단계는 {@code ml.predictor.enabled} 공유.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "transfer.predict")
public class TransferPredictProperties {

    /** 단일 요청 전체 deadline(ms). 각 외부 콜 전 잔여시간 검사, 초과 시 LIMITED. */
    private long deadlineMs = 2500;

    /** userArrivalAt 미래 상한(분). 모델 horizon. 초과 요청은 400. */
    private long maxFutureMinutes = 40;

    /** userArrivalAt 과거 허용 여유(초). 이보다 과거면 400. */
    private long pastGraceSeconds = 60;
}
