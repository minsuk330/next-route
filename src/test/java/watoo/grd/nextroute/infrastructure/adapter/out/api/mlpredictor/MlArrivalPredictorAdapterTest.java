package watoo.grd.nextroute.infrastructure.adapter.out.api.mlpredictor;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.route.config.MlPredictorProperties;
import watoo.grd.nextroute.application.route.exception.MlPredictionException;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlFeatureVector;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPrediction;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPredictionStatus;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MlArrivalPredictorAdapterTest {

    private MockWebServer server;
    private MlArrivalPredictorAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        MlPredictorProperties properties = new MlPredictorProperties();
        properties.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        properties.setTimeoutMs(500);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(Duration.ofMillis(500));
        RestClient restClient = RestClient.builder().requestFactory(factory).build();

        adapter = new MlArrivalPredictorAdapter(restClient, properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private List<MlFeatureVector> vectors(String... ids) {
        return java.util.Arrays.stream(ids)
                .map(id -> new MlFeatureVector(id, Map.of("route_id", "143")))
                .toList();
    }

    private MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void TC_정상_AVAILABLE와_UNSUPPORTED_혼합_requestId_결합() {
        server.enqueue(json("""
                {"results":[
                  {"request_id":"b","status":"UNSUPPORTED_ROUTE","seconds_to_arrival":null,"model_version":"v1"},
                  {"request_id":"a","status":"AVAILABLE","seconds_to_arrival":300.5,"model_version":"v1"}
                ]}"""));

        List<MlPrediction> results = adapter.predict(vectors("a", "b"));

        // 응답 순서가 뒤집혀도 입력(a,b) 순서로 결합.
        assertThat(results).extracting(MlPrediction::requestId).containsExactly("a", "b");
        assertThat(results.get(0).status()).isEqualTo(MlPredictionStatus.AVAILABLE);
        assertThat(results.get(0).secondsToArrival()).isEqualTo(300.5);
        assertThat(results.get(1).status()).isEqualTo(MlPredictionStatus.UNSUPPORTED_ROUTE);
        assertThat(results.get(1).secondsToArrival()).isNull();
    }

    @Test
    void TC_503_모델없음은_retryable_예외() {
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThatThrownBy(() -> adapter.predict(vectors("a")))
                .isInstanceOf(MlPredictionException.class)
                .satisfies(e -> assertThat(((MlPredictionException) e).isRetryable()).isTrue());
    }

    @Test
    void TC_422_계약위반은_non_retryable_예외() {
        server.enqueue(new MockResponse().setResponseCode(422).setBody("{\"detail\":\"missing\"}"));

        assertThatThrownBy(() -> adapter.predict(vectors("a")))
                .isInstanceOf(MlPredictionException.class)
                .satisfies(e -> assertThat(((MlPredictionException) e).isRetryable()).isFalse());
    }

    @Test
    void TC_타임아웃은_retryable_예외() {
        // 서버가 응답을 보내지 않아 read timeout(500ms) 발생 → I/O 실패는 재시도 가능.
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        assertThatThrownBy(() -> adapter.predict(vectors("a")))
                .isInstanceOf(MlPredictionException.class)
                .satisfies(e -> assertThat(((MlPredictionException) e).isRetryable()).isTrue());
    }

    @Test
    void TC_응답_requestId_누락은_불변식_위반_예외() {
        server.enqueue(json("""
                {"results":[
                  {"request_id":"a","status":"AVAILABLE","seconds_to_arrival":100.0,"model_version":"v1"}
                ]}"""));

        assertThatThrownBy(() -> adapter.predict(vectors("a", "b")))
                .isInstanceOf(MlPredictionException.class);
    }

    @Test
    void TC_응답_미요청_requestId는_불변식_위반_예외() {
        server.enqueue(json("""
                {"results":[
                  {"request_id":"a","status":"AVAILABLE","seconds_to_arrival":100.0,"model_version":"v1"},
                  {"request_id":"zzz","status":"AVAILABLE","seconds_to_arrival":100.0,"model_version":"v1"}
                ]}"""));

        assertThatThrownBy(() -> adapter.predict(vectors("a")))
                .isInstanceOf(MlPredictionException.class);
    }

    @Test
    void TC_AVAILABLE인데_seconds_null이면_예외() {
        server.enqueue(json("""
                {"results":[
                  {"request_id":"a","status":"AVAILABLE","seconds_to_arrival":null,"model_version":"v1"}
                ]}"""));

        assertThatThrownBy(() -> adapter.predict(vectors("a")))
                .isInstanceOf(MlPredictionException.class);
    }

    @Test
    void TC_빈_입력은_빈_결과() {
        assertThat(adapter.predict(List.of())).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }
}
