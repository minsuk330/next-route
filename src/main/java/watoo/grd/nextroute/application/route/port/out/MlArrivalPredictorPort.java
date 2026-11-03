package watoo.grd.nextroute.application.route.port.out;

import java.time.Instant;
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

    /** 요청 전체 deadline에서 남은 시간까지만 ML 응답을 기다리는 검색 경로용 호출. */
    default List<MlPrediction> predict(List<MlFeatureVector> vectors, long timeoutMs) {
        return predict(vectors);
    }

    /**
     * @param requestId  결과 재결합 키(배치 내 유일).
     * @param features   feature 이름→값. 이름은 학습(train.py)과 동일. 값은 숫자 또는 route_id(String).
     *                   null 값 허용(serving이 NaN으로 처리).
     * @param vehicleId  이 feature 의 바탕이 된 관측 차량. serving 이 노선 이력에서 이 차량의 궤적을
     *                   찾아 rt_* 피처를 만든다. null 이면 rt_* 는 NaN.
     * @param snapshotAt 그 관측 시각(dataTm). vehicleId 와 함께 쓰인다.
     */
    record MlFeatureVector(String requestId, Map<String, Object> features,
                           String vehicleId, Instant snapshotAt) {
        public MlFeatureVector(String requestId, Map<String, Object> features) {
            this(requestId, features, null, null);
        }
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
