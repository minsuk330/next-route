package watoo.grd.nextroute.infrastructure.adapter.out.api.mlpredictor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort;
import watoo.grd.nextroute.application.route.port.out.MlPredictionCachePort;
import watoo.grd.nextroute.application.route.service.MlFeatureCacheKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ML 예측 캐시 데코레이터.
 *
 * <p>같은 차량·같은 관측 시점에 대한 예측을 두 번 하지 않는다. 여정 평가기는 같은 노선을 기다리는
 * 사용자 수만큼 이 포트를 부르는데, <b>그 요청들의 입력이 글자 그대로 같다</b> — feature 는 차량
 * 관측 스냅샷에서만 나오고 사용자 도착 시각이 들어가는 자리가 없기 때문이다. 어느 차량을 탈지
 * 고르는 일은 이 뒤에서 여정마다 따로 한다.
 *
 * <p><b>배치에서 빠진 것만 골라 부른다.</b> 세 대 중 두 대가 캐시에 있으면 serving 에는 한 대만
 * 보낸다. 전부 있으면 아예 부르지 않는다 — 이 데코레이터의 존재 이유다.
 *
 * <p>캐시 효율은 상류에 매달린다. 버스 위치 응답이 {@code CachedSearchTimeBusAdapter} 에서 캐시되기
 * 때문에 그 15초 동안 모든 요청이 같은 {@code dataTm} 을 보고, 그래서 키가 같아진다.
 * <b>{@code route.transfer-arrival.cache-ttl-seconds} 를 0 으로 두면 이 캐시도 상시 miss 가 된다</b>
 * (호출량이 오늘 수준으로 되돌아갈 뿐 틀린 답을 주지는 않는다).
 *
 * <p><b>여기에는 single-flight 이 없다.</b> 호출 단위가 배치이고 배치 구성이 요청마다
 * 달라(차량 유효성 판정이 시각에 걸린다) 배치 키가 재사용되지 않는다. 평가 worker 는 소수의
 * 스레드라 세대 경계의 동시 miss 도 그 수를 넘지 않는다. 지하철 쪽은 호출 단위가 역 하나로 명확해서
 * single-flight 을 붙였다.
 */
@Slf4j
@Primary
@Component
public class CachedMlArrivalPredictorAdapter implements MlArrivalPredictorPort {

    private final MlArrivalPredictorAdapter delegate;
    private final MlPredictionCachePort cache;
    private final RouteSearchMetrics metrics;

    public CachedMlArrivalPredictorAdapter(MlArrivalPredictorAdapter delegate,
                                           MlPredictionCachePort cache,
                                           RouteSearchMetrics metrics) {
        this.delegate = delegate;
        this.cache = cache;
        this.metrics = metrics;
    }

    @Override
    public List<MlPrediction> predict(List<MlFeatureVector> vectors) {
        return predictInternal(vectors, null);
    }

    @Override
    public List<MlPrediction> predict(List<MlFeatureVector> vectors, long timeoutMs) {
        return predictInternal(vectors, timeoutMs);
    }

    private List<MlPrediction> predictInternal(List<MlFeatureVector> vectors, Long timeoutMs) {
        if (vectors == null || vectors.isEmpty()) {
            return List.of();
        }

        long startedAt = System.nanoTime();
        Map<String, String> keyByRequestId = new LinkedHashMap<>();
        for (MlFeatureVector vector : vectors) {
            keyByRequestId.put(vector.requestId(), MlFeatureCacheKey.of(vector.features()));
        }

        Map<String, MlPrediction> cached = findCached(
                keyByRequestId.values().stream().distinct().toList());

        List<MlFeatureVector> missing = new ArrayList<>();
        for (MlFeatureVector vector : vectors) {
            boolean hit = cached.containsKey(keyByRequestId.get(vector.requestId()));
            metrics.recordMlCacheAccess(hit ? "hit" : "miss");
            if (!hit) {
                missing.add(vector);
            }
        }

        Map<String, MlPrediction> freshByRequestId = missing.isEmpty()
                ? Map.of()
                : callAndStore(missing, keyByRequestId, remainingBudget(timeoutMs, startedAt));

        return merge(vectors, keyByRequestId, cached, freshByRequestId);
    }

    /**
     * 캐시 조회. 실패하면 전부 miss 로 본다.
     *
     * <p>포트 구현이 이미 장애를 삼키지만 여기서도 막는다. <b>캐시는 예측을 빠르게 만들 뿐이지
     * 예측의 전제가 아니다.</b> 구현이 바뀌어 예외가 새어 나와도 예측이 죽어서는 안 된다.
     */
    private Map<String, MlPrediction> findCached(List<String> cacheKeys) {
        try {
            Map<String, MlPrediction> found = cache.findAll(cacheKeys);
            return found == null ? Map.of() : found;
        } catch (Exception e) {
            log.warn("[MlPredictionCache] 조회 실패 — 전부 miss 로 진행한다: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 저장 실패는 다음 호출이 다시 부르면 그만이다. 이번 응답까지 버릴 이유가 없다. */
    private void store(Map<String, MlPrediction> toStore) {
        try {
            cache.putAll(toStore);
        } catch (Exception e) {
            log.warn("[MlPredictionCache] 저장 실패 — 다음 호출이 다시 예측한다: {}", e.getMessage());
        }
    }

    /**
     * 남은 예산. 캐시 조회에 쓴 시간을 뺀다.
     *
     * <p>0 이하가 되면 위임 어댑터가 예산 소진 예외를 던진다 — 캐시가 없던 때와 같은 동작이다.
     * 예산을 쓰지 않는 유일한 길은 캐시가 전부 맞아 serving 을 아예 부르지 않는 경우다.
     */
    private Long remainingBudget(Long timeoutMs, long startedAt) {
        if (timeoutMs == null) {
            return null;
        }
        long spentMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        return timeoutMs - spentMs;
    }

    private Map<String, MlPrediction> callAndStore(List<MlFeatureVector> missing,
                                                   Map<String, String> keyByRequestId,
                                                   Long timeoutMs) {
        List<MlPrediction> fresh = timeoutMs == null
                ? delegate.predict(missing)
                : delegate.predict(missing, timeoutMs);

        Map<String, MlPrediction> byRequestId = new HashMap<>();
        Map<String, MlPrediction> toStore = new HashMap<>();
        for (MlPrediction prediction : fresh) {
            byRequestId.put(prediction.requestId(), prediction);
            String cacheKey = keyByRequestId.get(prediction.requestId());
            if (cacheKey != null) {
                toStore.put(cacheKey, prediction);
            }
        }
        store(toStore);
        return byRequestId;
    }

    /**
     * 입력 순서대로 되돌린다.
     *
     * <p>캐시에 저장하지 않은 {@code requestId} 를 여기서 다시 채운다. 위임 어댑터가 입력·출력의
     * {@code requestId} 집합 일치를 강제하므로, 이 결합이 어긋나면 호출자가 결과를 못 찾는다.
     */
    private List<MlPrediction> merge(List<MlFeatureVector> vectors,
                                     Map<String, String> keyByRequestId,
                                     Map<String, MlPrediction> cached,
                                     Map<String, MlPrediction> freshByRequestId) {
        List<MlPrediction> merged = new ArrayList<>(vectors.size());
        for (MlFeatureVector vector : vectors) {
            MlPrediction hit = cached.get(keyByRequestId.get(vector.requestId()));
            if (hit != null) {
                merged.add(new MlPrediction(vector.requestId(), hit.status(),
                        hit.secondsToArrival(), hit.modelVersion()));
                continue;
            }
            MlPrediction fresh = freshByRequestId.get(vector.requestId());
            if (fresh != null) {
                merged.add(fresh);
            }
        }
        return merged;
    }
}
