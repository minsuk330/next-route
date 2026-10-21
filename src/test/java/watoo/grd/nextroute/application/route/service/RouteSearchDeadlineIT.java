package watoo.grd.nextroute.application.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import watoo.grd.nextroute.application.route.config.OdSayProperties;
import watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties;
import watoo.grd.nextroute.application.route.config.TmapProperties;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.exception.OdSayApiException;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.WalkSegmentCachePort;
import watoo.grd.nextroute.infrastructure.adapter.out.api.odsay.OdSayApiAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.api.tmap.TmapPedestrianAdapter;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Step 2 검증 — 시간 예산(deadline)과 조건부 재시도가 적용된 뒤의 동작.
 * Step 0 baseline(같은 시나리오, RESULTS.md)과 짝을 이룬다.
 *
 * <p>확인 대상:
 * <ul>
 *   <li>선택 보강(TMAP)의 지연이 응답 시간을 끌지 않는다 — 단계 상한에서 잘리고 PARTIAL/BASE_ONLY 로 반환</li>
 *   <li>필수 provider(ODsay) 무응답도 예산 안에서 끝난다 — 3회 재시도 + 6s backoff 소멸</li>
 *   <li>4xx 는 재시도하지 않는다 — 같은 답을 다시 받으려고 시간을 쓰지 않는다</li>
 * </ul>
 */
class RouteSearchDeadlineIT {

    /** ODSAY 롤백 구성. 기본값에 기대지 않고 명시한다 — P7 부터 코드 기본값은 OTP 다. */
    private static watoo.grd.nextroute.application.route.config.RoutingProviderProperties odsayProps() {
        var props = new watoo.grd.nextroute.application.route.config.RoutingProviderProperties();
        props.setDefaultProvider(watoo.grd.nextroute.application.route.routing.RoutingProvider.ODSAY);
        return props;
    }


    /** ODsay 응답: 도보 1구간짜리 경로 1개. mapObj 가 없어 폴리라인 보강은 적용 대상이 아니다. */
    private static final String ODSAY_BODY = """
            {"result":{"searchType":0,"busCount":0,"subwayCount":0,"trainCount":0,"airCount":0,
              "path":[{"pathType":3,
                "info":{"totalTime":10,"totalPayment":0,"totalWalk":640,"totalDistance":640,
                        "firstStartStation":"출발역","lastEndStation":"도착역"},
                "subPath":[{"trafficType":3,"sectionTime":10,"distance":640,
                            "startX":127.0,"startY":37.5,"endX":127.01,"endY":37.51,
                            "startName":"출발","endName":"도착"}]}]}}
            """;

    private static final String TMAP_BODY = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature",
               "geometry":{"type":"LineString","coordinates":[[127.0,37.5],[127.01,37.51]]},
               "properties":{"totalDistance":640,"totalTime":600,"index":0,"pointIndex":0,
                             "description":"이동","turnType":11}}]}
            """;

    private MockWebServer odsayServer;
    private MockWebServer tmapServer;
    private SimpleMeterRegistry registry;
    private RouteSearchService service;

    /** 이 테스트의 시간 예산. 전체 1.5s, ODsay 800ms, TMAP 400ms — 상한이 실제로 자르는지 본다. */
    private static final long DEADLINE_MS = 1500;
    private static final long ODSAY_LIMIT_MS = 800;
    private static final long TMAP_LIMIT_MS = 400;

    private static RouteSearchDeadlineProperties deadlineProperties() {
        RouteSearchDeadlineProperties p = new RouteSearchDeadlineProperties();
        p.setDeadlineMs(DEADLINE_MS);
        p.setOdsayMs(ODSAY_LIMIT_MS);
        p.setTmapMs(TMAP_LIMIT_MS);
        return p;
    }

    @BeforeEach
    void setUp() throws Exception {
        odsayServer = new MockWebServer();
        tmapServer = new MockWebServer();
        odsayServer.start();
        tmapServer.start();

        registry = new SimpleMeterRegistry();
        RouteSearchMetrics metrics = new RouteSearchMetrics(registry);

        OdSayProperties odSayProperties = new OdSayProperties();
        odSayProperties.setKey("test-key");
        odSayProperties.setBaseUrl(odsayServer.url("/").toString().replaceAll("/$", ""));

        // ODsay searchPath 는 요청별 read timeout 을 걸려고 RestClient 를 호출마다 만든다.
        OdSayApiAdapter odSayAdapter =
                new OdSayApiAdapter(new ObjectMapper(), odSayProperties, metrics, RestClient.builder());

        TmapProperties tmapProperties = new TmapProperties();
        tmapProperties.setAppKey("test-key");
        tmapProperties.setBaseUrl(tmapServer.url("/").toString().replaceAll("/$", ""));
        tmapProperties.setRetryOnFailure(true);   // 운영 기본값. 이제 4xx 는 재시도 대상에서 빠진다.
        TmapPedestrianAdapter tmapAdapter =
                new TmapPedestrianAdapter(RestClient.builder().build(), tmapProperties, metrics);

        WalkSegmentCachePort walkCache = mock(WalkSegmentCachePort.class);
        when(walkCache.get(any())).thenReturn(Optional.empty());   // 항상 miss — provider 경로 측정

        WalkSegmentEnricher walkEnricher =
                new WalkSegmentEnricher(new WalkCoordResolver(), walkCache, tmapAdapter,
                        Executors.newFixedThreadPool(4), metrics);
        service = new RouteSearchService(
                odSayAdapter, tmapAdapter,
                walkEnricher, mock(TransferArrivalEnricher.class), metrics,
                deadlineProperties(),
                new watoo.grd.nextroute.application.route.routing.RoutingProviderSelector(
                        odsayProps(), java.util.List.of()),
                new LegacyRouteResponseMapper(mock(watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver.class)),
                new DepartureTimeResolver(java.time.Clock.systemDefaultZone()),
                mock(watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate.class),
                java.time.Clock.systemDefaultZone(), new watoo.grd.nextroute.application.route.config.OtpRoutingProperties(),
                new WalkCoordResolver());
    }

    @AfterEach
    void tearDown() throws Exception {
        odsayServer.shutdown();
        tmapServer.shutdown();
    }

    // ── 시나리오 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("정상(ODsay 20ms, TMAP 20ms): 예산은 정상 응답을 방해하지 않는다")
    void normal() {
        odsayServer.setDispatcher(fixed(ODSAY_BODY, 20));
        tmapServer.setDispatcher(fixed(TMAP_BODY, 20));

        RouteSearchResult result = service.searchWithoutRealtime(request());

        assertThat(result.paths()).hasSize(1);
        assertThat(searchCount("full")).isEqualTo(1);
        report("정상(ODsay 20ms / TMAP 20ms)");
    }

    @Test
    @DisplayName("TMAP 5s 지연: 선택 보강은 단계 상한에서 잘리고 기본 경로가 즉시 반환된다")
    void slowTmapIsCutAtStageLimit() {
        odsayServer.setDispatcher(fixed(ODSAY_BODY, 20));
        tmapServer.setDispatcher(fixed(TMAP_BODY, 5_000));

        long startNanos = System.nanoTime();
        RouteSearchResult result = service.searchWithoutRealtime(request());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        report("TMAP 5s 지연");
        System.out.printf("  전체 검색 소요: %,d ms (before: 5,197ms)%n", elapsedMs);

        // baseline 은 5,197ms 였다. 이제 TMAP 단계 상한(400ms)에서 잘린다.
        assertThat(elapsedMs).isLessThan(DEADLINE_MS);
        assertThat(result.paths()).hasSize(1);          // 기본 경로는 그대로 반환
        assertThat(searchCount("base_only")).isEqualTo(1);
        assertThat(degradedCount("walk", "timeout")).isEqualTo(1);
    }

    @Test
    @DisplayName("ODsay 무응답: 예산 안에서 종료한다 — 3회 재시도와 6s backoff 소멸")
    void odsayNoResponseEndsWithinBudget() {
        odsayServer.setDispatcher(noResponse());

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> service.searchWithoutRealtime(request()))
                .isInstanceOf(OdSayApiException.class)
                .satisfies(e -> assertThat(((OdSayApiException) e).getKind())
                        .isEqualTo(OdSayApiException.Kind.TIMEOUT));   // → 504 로 매핑됨
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        report("ODsay 무응답");
        System.out.printf("  전체 검색 소요: %,d ms (before: 6,934ms)%n", elapsedMs);

        // ODsay 단계 상한(800ms) 안에서 끝난다. 첫 시도가 예산을 다 쓰므로 재시도는 성립하지 않는다.
        assertThat(elapsedMs).isLessThan(DEADLINE_MS);
        assertThat(odsayServer.getRequestCount()).isEqualTo(1);
        assertThat(searchCount("failed")).isEqualTo(1);
        assertThat(deadlineExceededCount("odsay")).isEqualTo(1);
    }

    @Test
    @DisplayName("ODsay 5xx: 예산이 남아 있으면 1회만 재시도한다")
    void odsayServerErrorRetriesOnce() {
        odsayServer.setDispatcher(status(500, 10));

        assertThatThrownBy(() -> service.searchWithoutRealtime(request()))
                .isInstanceOf(OdSayApiException.class)
                .satisfies(e -> assertThat(((OdSayApiException) e).getKind())
                        .isEqualTo(OdSayApiException.Kind.UPSTREAM));  // → 502 로 매핑됨

        report("ODsay 500");

        // baseline 은 3회였다. 이제 최대 2회(원 호출 + 재시도 1회).
        assertThat(odsayServer.getRequestCount()).isEqualTo(2);
        assertThat(externalCallCount("odsay", "error", "true")).isEqualTo(1);
    }

    @Test
    @DisplayName("TMAP 4xx: 재시도하지 않는다 — 같은 답을 다시 받으려고 시간을 쓰지 않는다")
    void tmapClientErrorIsNotRetried() {
        odsayServer.setDispatcher(fixed(ODSAY_BODY, 20));
        tmapServer.setDispatcher(status(400, 10));

        RouteSearchResult result = service.searchWithoutRealtime(request());

        report("TMAP 400");

        assertThat(result.paths()).hasSize(1);                 // 기본 경로 유지
        assertThat(tmapServer.getRequestCount()).isEqualTo(1);  // before: 2 (4xx도 재시도했음)
        assertThat(externalCallCount("tmap", "error", "false")).isEqualTo(1);
        assertThat(degradedCount("walk", "upstream_error")).isEqualTo(1);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private RouteSearchRequest request() {
        return new RouteSearchRequest(127.0, 37.5, 127.01, 37.51, "출발지", "도착지");
    }

    private static Dispatcher fixed(String body, long delayMs) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBodyDelay(delayMs, TimeUnit.MILLISECONDS)
                        .setBody(body);
            }
        };
    }

    private static Dispatcher status(int code, long delayMs) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse()
                        .setResponseCode(code)
                        .setBodyDelay(delayMs, TimeUnit.MILLISECONDS)
                        .setBody("{}");
            }
        };
    }

    /** 소켓은 열리지만 응답을 주지 않음 → client read timeout 유발. */
    private static Dispatcher noResponse() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE);
            }
        };
    }

    private double searchCount(String outcome) {
        Timer timer = registry.find(RouteSearchMetrics.SEARCH_DURATION).tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private double degradedCount(String component, String reason) {
        Counter counter = registry.find(RouteSearchMetrics.DEGRADED)
                .tag("component", component).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private double deadlineExceededCount(String stage) {
        Counter counter = registry.find(RouteSearchMetrics.DEADLINE_EXCEEDED).tag("stage", stage).counter();
        return counter == null ? 0 : counter.count();
    }

    private double externalCallCount(String provider, String outcome, String retry) {
        Counter counter = registry.find(RouteSearchMetrics.EXTERNAL_CALLS)
                .tag("provider", provider).tag("outcome", outcome).tag("retry", retry).counter();
        return counter == null ? 0 : counter.count();
    }

    /** RESULTS.md 원자료 — 단계별 소요와 provider 호출을 그대로 출력(Step 0 baseline 과 같은 형식). */
    private void report(String scenario) {
        System.out.printf("%n=== step2(after): %s ===%n", scenario);
        registry.find(RouteSearchMetrics.SEARCH_DURATION).timers().forEach(t ->
                System.out.printf("  route.search.duration[outcome=%s] count=%d total=%,.0f ms max=%,.0f ms%n",
                        t.getId().getTag("outcome"), t.count(),
                        t.totalTime(TimeUnit.MILLISECONDS), t.max(TimeUnit.MILLISECONDS)));
        registry.find(RouteSearchMetrics.STAGE_DURATION).timers().forEach(t ->
                System.out.printf("  stage[%s/%s] count=%d total=%,.0f ms%n",
                        t.getId().getTag("stage"), t.getId().getTag("outcome"),
                        t.count(), t.totalTime(TimeUnit.MILLISECONDS)));
        registry.find(RouteSearchMetrics.EXTERNAL_CALLS).counters().forEach(c ->
                System.out.printf("  external.api.calls[provider=%s outcome=%s retry=%s] = %,.0f%n",
                        c.getId().getTag("provider"), c.getId().getTag("outcome"),
                        c.getId().getTag("retry"), c.count()));
        registry.find(RouteSearchMetrics.EXTERNAL_DURATION).timers().forEach(t ->
                System.out.printf("  external.api.duration[provider=%s outcome=%s] total=%,.0f ms%n",
                        t.getId().getTag("provider"), t.getId().getTag("outcome"),
                        t.totalTime(TimeUnit.MILLISECONDS)));
        List<Counter> degraded = registry.find(RouteSearchMetrics.DEGRADED).counters().stream().toList();
        degraded.forEach(c ->
                System.out.printf("  degraded[component=%s reason=%s] = %,.0f%n",
                        c.getId().getTag("component"), c.getId().getTag("reason"), c.count()));
    }
}
