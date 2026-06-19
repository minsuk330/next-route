package watoo.grd.nextroute.application.route.port.out;

import java.util.List;
import java.util.Map;

/**
 * 버스 도착예측 ML serving 호출 포트.
 *
 * <p>요청/응답은 순서가 아니라 {@code requestId}로 결합한다. 한 batch에 여러 차량 feature를
 * 담아 단일 호출로 예측한다. 미등록 route 등 item별 상태는 {@link MlPrediction#status()}로 전달되며,
 * 입력/출력 requestId 집합 불일치·status별 필드 불변식 위반·호출 실패는
 * {@link watoo.grd.nextroute.application.route.exception.MlPredictionException}으로 처리한다.
 */
public interface MlArrivalPredictorPort {

    List<MlPrediction> predict(List<MlFeatureVector> vectors);

    /**
     * @param requestId 결과 재결합 키(배치 내 유일).
     * @param features  feature 이름→값. 이름은 학습(train.py)과 동일. 값은 숫자 또는 route_id(String).
     *                  null 값 허용(serving이 NaN으로 처리).
     */
    record MlFeatureVector(String requestId, Map<String, Object> features) {
    }

    /**
     * @param secondsToArrival AVAILABLE이면 non-null(finite·non-negative), 그 외 null.
     */
    record MlPrediction(String requestId, MlPredictionStatus status,
                        Double secondsToArrival, String modelVersion) {
    }

    enum MlPredictionStatus {
        AVAILABLE,
        UNSUPPORTED_ROUTE
    }
}
