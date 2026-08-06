package watoo.grd.nextroute.infrastructure.adapter.out.api.mlpredictor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.route.exception.MlPredictionException;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlFeatureVector;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPrediction;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPredictionStatus;
import watoo.grd.nextroute.application.route.port.out.MlPredictionCachePort;
import watoo.grd.nextroute.application.route.service.MlFeatureCacheKey;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 같은 입력을 두 번 예측하지 않는지 본다.
 *
 * <p>여기서 못박는 것은 "전부 캐시에 있으면 serving 을 아예 부르지 않는다"와 "일부만 있으면 빠진
 * 것만 부른다"다. 전자가 이 PR 의 목적이고, 후자가 없으면 차량이 한 대만 새로 나타나도 배치 전체를
 * 다시 부르게 된다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ML 예측 캐시 데코레이터")
class CachedMlArrivalPredictorAdapterTest {

    @Mock MlArrivalPredictorAdapter delegate;
    @Mock MlPredictionCachePort cache;

    CachedMlArrivalPredictorAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CachedMlArrivalPredictorAdapter(
                delegate, cache, new RouteSearchMetrics(new SimpleMeterRegistry()));
    }

    /** 차량 하나의 feature. 값이 다르면 키도 다르다. */
    private MlFeatureVector vector(String vehicleId, int sectionOrder) {
        Map<String, Object> features = new HashMap<>();
        features.put("route_id", "R1");
        features.put("target_seq", 12);
        features.put("current_section_order", sectionOrder);
        return new MlFeatureVector("R1:" + vehicleId, features);
    }

    private MlPrediction available(String requestId, double seconds) {
        return new MlPrediction(requestId, MlPredictionStatus.AVAILABLE, seconds, "v3");
    }

    /** 캐시에 들어 있는 값에는 requestId 가 없다(배치 종속 필드라 저장하지 않는다). */
    private MlPrediction cached(double seconds) {
        return new MlPrediction(null, MlPredictionStatus.AVAILABLE, seconds, "v3");
    }

    private void givenCache(Map<String, MlPrediction> byCacheKey) {
        willAnswer(invocation -> {
            List<String> keys = invocation.getArgument(0);
            Map<String, MlPrediction> found = new HashMap<>();
            keys.stream().filter(byCacheKey::containsKey)
                    .forEach(key -> found.put(key, byCacheKey.get(key)));
            return found;
        }).given(cache).findAll(anyList());
    }

    @Nested
    @DisplayName("캐시가 맞을 때")
    class WhenCached {

        @Test
        void TC_전부_hit이면_serving을_부르지_않는다() {
            MlFeatureVector v1 = vector("v1", 3);
            MlFeatureVector v2 = vector("v2", 5);
            givenCache(Map.of(
                    MlFeatureCacheKey.of(v1.features()), cached(100),
                    MlFeatureCacheKey.of(v2.features()), cached(200)));

            List<MlPrediction> result = adapter.predict(List.of(v1, v2));

            verify(delegate, never()).predict(anyList());
            assertThat(result).hasSize(2);
            assertThat(result).extracting(MlPrediction::requestId)
                    .containsExactly("R1:v1", "R1:v2");   // 저장하지 않은 결합 키를 다시 채운다
            assertThat(result).extracting(MlPrediction::secondsToArrival)
                    .containsExactly(100.0, 200.0);
        }

        @Test
        void TC_일부만_hit이면_빠진_것만_부른다() {
            MlFeatureVector v1 = vector("v1", 3);
            MlFeatureVector v2 = vector("v2", 5);
            MlFeatureVector v3 = vector("v3", 7);
            givenCache(Map.of(
                    MlFeatureCacheKey.of(v1.features()), cached(100),
                    MlFeatureCacheKey.of(v3.features()), cached(300)));
            willAnswer(invocation -> List.of(available("R1:v2", 200)))
                    .given(delegate).predict(anyList());

            List<MlPrediction> result = adapter.predict(List.of(v1, v2, v3));

            ArgumentCaptor<List<MlFeatureVector>> sent = ArgumentCaptor.captor();
            verify(delegate).predict(sent.capture());
            assertThat(sent.getValue()).extracting(MlFeatureVector::requestId)
                    .containsExactly("R1:v2");

            assertThat(result).extracting(MlPrediction::requestId)
                    .containsExactly("R1:v1", "R1:v2", "R1:v3");   // 입력 순서 유지
        }

        @Test
        void TC_새로_받은_예측만_저장한다() {
            MlFeatureVector v1 = vector("v1", 3);
            MlFeatureVector v2 = vector("v2", 5);
            givenCache(Map.of(MlFeatureCacheKey.of(v1.features()), cached(100)));
            willAnswer(invocation -> List.of(available("R1:v2", 200)))
                    .given(delegate).predict(anyList());

            adapter.predict(List.of(v1, v2));

            ArgumentCaptor<Map<String, MlPrediction>> stored = ArgumentCaptor.captor();
            verify(cache).putAll(stored.capture());
            assertThat(stored.getValue()).containsOnlyKeys(MlFeatureCacheKey.of(v2.features()));
        }
    }

    @Nested
    @DisplayName("캐시가 없거나 망가졌을 때")
    class WhenCacheUnavailable {

        @Test
        void TC_전부_miss면_그대로_serving을_부른다() {
            MlFeatureVector v1 = vector("v1", 3);
            givenCache(Map.of());
            willAnswer(invocation -> List.of(available("R1:v1", 100)))
                    .given(delegate).predict(anyList());

            List<MlPrediction> result = adapter.predict(List.of(v1));

            verify(delegate).predict(anyList());
            assertThat(result).hasSize(1);
        }

        @Test
        void TC_캐시_읽기가_터져도_예측은_계속된다() {
            MlFeatureVector v1 = vector("v1", 3);
            willThrow(new IllegalStateException("redis down")).given(cache).findAll(anyList());
            willAnswer(invocation -> List.of(available("R1:v1", 100)))
                    .given(delegate).predict(anyList());

            List<MlPrediction> result = adapter.predict(List.of(v1));

            // 캐시는 예측을 빠르게 만들 뿐 전제가 아니다. Redis 가 죽으면 캐시 없던 때로 퇴화한다.
            assertThat(result).extracting(MlPrediction::requestId).containsExactly("R1:v1");
        }

        @Test
        void TC_캐시_저장이_터져도_응답은_돌아간다() {
            MlFeatureVector v1 = vector("v1", 3);
            givenCache(Map.of());
            willAnswer(invocation -> List.of(available("R1:v1", 100)))
                    .given(delegate).predict(anyList());
            willThrow(new IllegalStateException("redis down")).given(cache).putAll(anyMap());

            assertThat(adapter.predict(List.of(v1))).hasSize(1);
        }

        @Test
        void TC_serving_예외는_그대로_전파된다() {
            MlFeatureVector v1 = vector("v1", 3);
            givenCache(Map.of());
            willThrow(new MlPredictionException(503, "serving down", true))
                    .given(delegate).predict(anyList());

            assertThatThrownBy(() -> adapter.predict(List.of(v1)))
                    .isInstanceOf(MlPredictionException.class);
        }
    }

    @Test
    void TC_빈_입력이면_아무도_부르지_않는다() {
        assertThat(adapter.predict(List.of())).isEmpty();

        verify(delegate, never()).predict(anyList());
        verify(cache, never()).findAll(anyList());
        verify(cache, never()).putAll(anyMap());
    }

    @Test
    void TC_예산이_있는_호출도_같은_경로를_탄다() {
        MlFeatureVector v1 = vector("v1", 3);
        givenCache(Map.of(MlFeatureCacheKey.of(v1.features()), cached(100)));

        List<MlPrediction> result = adapter.predict(List.of(v1), 1_500);

        // 전부 hit 이면 예산을 아예 쓰지 않는다 — 검색 deadline 이 그만큼 남는다.
        verify(delegate, never()).predict(anyList(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(result).hasSize(1);
    }
}
