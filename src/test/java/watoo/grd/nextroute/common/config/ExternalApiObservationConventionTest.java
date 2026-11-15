package watoo.grd.nextroute.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.web.client.HttpClientObservationsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 외부 API 호출 지표가 엔드포인트별로 갈리고, 인증키·역 이름·검색어가 지표와 span 에 남지 않는지.
 */
@DisplayName("외부 API 호출 관측 이름")
class ExternalApiObservationConventionTest {

    private static final String SEOUL_KEY = "4d6a4b7c8e9f0a1b2c3d4e5f6a7b8c9d";
    private static final String DATA_GO_KR_KEY = "abc%2Bdef%3D%3D";

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("어댑터가 실제로 부르는 주소 모양에서 API 이름만 남긴다")
    @CsvSource(delimiter = '|', value = {
            // 서울 지하철 도착: 키·쪽수·역 이름(인코딩된 한글)
            "/api/subway/" + SEOUL_KEY + "/json/realtimeStationArrival/0/1000/%EA%B0%95%EB%82%A8"
                    + " | /api/subway/{}/json/realtimeStationArrival/{}/{}/{}",
            "/api/subway/" + SEOUL_KEY + "/json/realtimeStationArrival/ALL"
                    + " | /api/subway/{}/json/realtimeStationArrival/ALL",
            "/api/subway/" + SEOUL_KEY + "/json/realtimePosition/0/100/2%ED%98%B8%EC%84%A0"
                    + " | /api/subway/{}/json/realtimePosition/{}/{}/{}",
            // 서울 열린데이터(포트 8088): 키가 첫 조각
            "/" + SEOUL_KEY + "/json/subwayStationMaster/1/1000/ | /{}/json/subwayStationMaster/{}/{}/",
            "/" + SEOUL_KEY + "/xml/busRoute/1/1000/ | /{}/xml/busRoute/{}/{}/",
            // 서울 버스: 키는 쿼리라 경로는 그대로
            "/api/rest/arrive/getArrInfoByRoute | /api/rest/arrive/getArrInfoByRoute",
            "/api/rest/busRouteInfo/getStaionByRoute | /api/rest/busRouteInfo/getStaionByRoute",
            // data.go.kr: 기관 번호는 가리고 서비스·오퍼레이션 이름(끝 숫자 포함)은 남긴다
            "/6410000/busarrivalservice/v2/getBusArrivalItemv2 | /{}/busarrivalservice/v2/getBusArrivalItemv2",
            "/1613000/ArvlInfoInqireService/getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList"
                    + " | /{}/ArvlInfoInqireService/getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList",
            // 부산교통공사 열차시각표(지방 지하철 시간표): 기관 번호 B551542 를 가린다
            "/B551542/trainTime/getTrainTime | /{}/trainTime/getTrainTime",
            "/1613000/SubwayInfo/GetSubwaySttnAcctoSchdulList | /{}/SubwayInfo/GetSubwaySttnAcctoSchdulList",
            "/v2/local/search/keyword.json | /v2/local/search/keyword.json"
    })
    void masksPathToApiNames(String rawPath, String expected) {
        assertThat(ExternalApiObservationConvention.maskPath(rawPath)).isEqualTo(expected);
    }

    @Test
    @DisplayName("영문자만으로 된 긴 16진 키도 가린다")
    void masksLetterOnlyHexKey() {
        assertThat(ExternalApiObservationConvention.maskPath("/abcdefabcdefabcdefabcdef/json/x"))
                .isEqualTo("/{}/json/x");
    }

    @Test
    @DisplayName("RestTemplate 에 완성된 URI 를 넘겨도 uri tag 가 엔드포인트별로 갈리고, yaml 의 1.5초 경계 구간을 내보낸다")
    void restTemplateUriTagIsPerEndpoint() {
        contextRunner().run(context -> {
            RestTemplate restTemplate = context.getBean(RestTemplateBuilder.class).build();
            MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
            server.expect(method(HttpMethod.GET)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            restTemplate.getForObject(URI.create("http://swopenAPI.seoul.go.kr/api/subway/" + SEOUL_KEY
                    + "/json/realtimeStationArrival/0/1000/%EA%B0%95%EB%82%A8"), String.class);

            Timer timer = context.getBean(MeterRegistry.class).get("http.client.requests").timer();
            assertThat(timer.getId().getTag("uri"))
                    .isEqualTo("/api/subway/{}/json/realtimeStationArrival/{}/{}/{}");
            assertThat(timer.getId().getTag("client.name")).isEqualTo("swopenAPI.seoul.go.kr");
            assertThat(Arrays.stream(timer.takeSnapshot().histogramCounts())
                    .map(CountAtBucket::bucket).map(b -> b / 1_000_000))
                    .contains(1500.0);
        });
    }

    @Test
    @DisplayName("RestClient 도 같은 규칙을 쓰고, span 의 http.url 에는 쿼리(serviceKey)가 없다")
    void restClientUsesSameConventionAndDropsQueryFromUrl() {
        contextRunner().run(context -> {
            List<ClientRequestObservationContext> seen = new ArrayList<>();
            context.getBean(ObservationRegistry.class).observationConfig()
                    .observationHandler(new CapturingHandler(seen));
            RestClient.Builder builder = context.getBean(RestClient.Builder.class);
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(method(HttpMethod.GET)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            builder.build().get()
                    .uri(URI.create("http://ws.bus.go.kr/api/rest/arrive/getArrInfoByRoute?serviceKey="
                            + DATA_GO_KR_KEY + "&stId=123"))
                    .retrieve().body(String.class);

            Timer timer = context.getBean(MeterRegistry.class).get("http.client.requests").timer();
            assertThat(timer.getId().getTag("uri")).isEqualTo("/api/rest/arrive/getArrInfoByRoute");
            assertThat(seen).hasSize(1);
            String url = context.getBean(ExternalApiObservationConvention.class)
                    .getHighCardinalityKeyValues(seen.get(0)).stream()
                    .filter(kv -> kv.getKey().equals("http.url")).findFirst().orElseThrow().getValue();
            assertThat(url).isEqualTo("http://ws.bus.go.kr/api/rest/arrive/getArrInfoByRoute");
        });
    }

    @Test
    @DisplayName("주소 틀이 있는 호출은 원래대로 틀을 쓴다")
    void templateStillWins() {
        contextRunner().run(context -> {
            RestTemplate restTemplate = context.getBean(RestTemplateBuilder.class).build();
            MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
            server.expect(method(HttpMethod.GET)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            restTemplate.getForObject("http://example.com/stations/{id}", String.class, 42);

            Timer timer = context.getBean(MeterRegistry.class).get("http.client.requests").timer();
            assertThat(timer.getId().getTag("uri")).isEqualTo("/stations/{id}");
        });
    }

    @Test
    @DisplayName("JDK 클라이언트 호출과 Spring 클라이언트 호출이 Prometheus 에 같은 이름으로 함께 나간다 — tag 열쇠가 다르면 한쪽이 버려진다")
    void jdkAndSpringCallsCoexistInPrometheus() throws Exception {
        // prometheus 레지스트리는 runtimeOnly 라 이름으로 불러온다.
        Class<?> prometheusExport = Class.forName("org.springframework.boot.actuate.autoconfigure.metrics."
                + "export.prometheus.PrometheusMetricsExportAutoConfiguration");
        okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
        server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("{}"));
        server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("{}"));
        server.start();
        try {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            ObservationAutoConfiguration.class,
                            MetricsAutoConfiguration.class,
                            prometheusExport,
                            HttpClientObservationsAutoConfiguration.class,
                            RestTemplateAutoConfiguration.class))
                    .withBean(ExternalApiObservationConvention.class)
                    .withBean(JdkHttpClientMetrics.class)
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .run(context -> {
                        String base = server.url("/").toString().replaceAll("/$", "");
                        context.getBean(RestTemplateBuilder.class).build()
                                .getForObject(URI.create(base + "/api/rest/arrive/getArrInfoByRouteAll"), String.class);
                        context.getBean(JdkHttpClientMetrics.class).send(java.net.http.HttpClient.newHttpClient(),
                                java.net.http.HttpRequest.newBuilder(URI.create(base + "/api/rest/arrive/getArrInfoByRoute"))
                                        .GET().build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString());

                        MeterRegistry registry = context.getBean(MeterRegistry.class);
                        String scrape = (String) registry.getClass().getMethod("scrape").invoke(registry);
                        assertThat(scrape)
                                .contains("uri=\"/api/rest/arrive/getArrInfoByRouteAll\"")
                                .contains("uri=\"/api/rest/arrive/getArrInfoByRoute\"")
                                .contains("http_client_requests_seconds_bucket");
                    });
        } finally {
            server.shutdown();
        }
    }

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ObservationAutoConfiguration.class,
                        MetricsAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class,
                        HttpClientObservationsAutoConfiguration.class,
                        RestTemplateAutoConfiguration.class,
                        RestClientAutoConfiguration.class))
                .withBean(ExternalApiObservationConvention.class)
                // 구간 경계는 실제 application.yaml 에서 읽는다 — 설정 키가 빠지면 구간 검증이 깨진다.
                .withInitializer(new ConfigDataApplicationContextInitializer());
    }

    private record CapturingHandler(List<ClientRequestObservationContext> seen)
            implements ObservationHandler<ClientRequestObservationContext> {

        @Override
        public void onStop(ClientRequestObservationContext context) {
            seen.add(context);
        }

        @Override
        public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
            return context instanceof ClientRequestObservationContext;
        }
    }
}
