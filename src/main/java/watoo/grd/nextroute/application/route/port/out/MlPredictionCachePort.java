package watoo.grd.nextroute.application.route.port.out;

import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPrediction;

import java.util.List;
import java.util.Map;

/**
 * ML 예측 결과 캐시 포트.
 *
 * <p>같은 feature 를 두 번 예측하지 않기 위한 것이다. 키는
 * {@link watoo.grd.nextroute.application.route.service.MlFeatureCacheKey} 가 만든다.
 *
 * <p><b>배치 단위로 읽는다.</b> 한 노선의 운행 차량이 수십 대라 키마다 왕복하면 Redis 왕복 시간이
 * ML 호출만큼 든다. 한 번에 묻고 있는 것만 받는다.
 */
public interface MlPredictionCachePort {

    /**
     * @return 캐시에 있던 것만. 없는 키는 아예 빠진다
     */
    Map<String, MlPrediction> findAll(List<String> cacheKeys);

    /**
     * <p>저장값에 {@code requestId} 는 넣지 않는다 — 배치 안에서만 유효한 결합 키라서, 다음 배치에
     * 그대로 붙으면 예측이 엉뚱한 차량에 매핑된다. 읽는 쪽이 자기 배치의 {@code requestId} 를 다시
     * 채운다.
     */
    void putAll(Map<String, MlPrediction> predictionsByCacheKey);
}
