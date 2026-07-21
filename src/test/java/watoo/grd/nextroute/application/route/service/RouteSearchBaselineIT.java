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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import watoo.grd.nextroute.application.route.config.OdSayProperties;
import watoo.grd.nextroute.application.route.config.TmapProperties;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.exception.OdSayApiException;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.WalkSegmentCachePort;
import watoo.grd.nextroute.domain.route.log.service.RouteDataService;
import watoo.grd.nextroute.domain.route.polyline.service.OdsayRoutePolylineDataService;
import watoo.grd.nextroute.infrastructure.adapter.out.api.odsay.OdSayApiAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.api.tmap.TmapPedestrianAdapter;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Step 0 baseline — 현재 코드의 동작을 <b>바꾸지 않고</b> 외부 provider 지연·실패가
 * 검색 응답시간에 어떻게 전파되는지 측정한다. 여기서 남는 수치가 이후 단계(Deadline·Single-flight·
 * Bulkhead)의 before 가 된다(PLAN.md §6 Step 0).
 *
 * <p>ODsay/TMAP 은 MockWebServer 로 지연·HTTP 상태를 제어하고, DB·캐시 의존은 stub 으로 고정해
 * 외부 호출 구간만 남긴다. 버스·ML·Redis 를 포함한 캐시 실험은 Step 1(PR-B)에서 Testcontainers 로 다룬다.
 *
 * <p>측정값은 stdout 에 남겨 RESULTS.md 의 원자료로 쓴다. 단정(assert)은 구조적 사실
 * (재시도 횟수, deadline 부재, 부분 실패 시 기본 경로 유지)에만 걸고, 절대 시간에는 걸지 않는다.
 */
class RouteSearchBaselineIT {

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

    /**
     * ODsay read timeout. 운영은 공용 RestTemplate 의 30s 지만, 재시도 3회를 실제로 태우면
     * 테스트가 90s 를 넘는다. 여기서는 짧게 두어 <b>backoff sleep 이 지배적</b>이라는 사실만 드러낸다
     * (2s + 4s = 6s 는 timeout 값과 무관하게 고정).
     */
    private static final Duration ODSAY_READ_TIMEOUT = Duration.ofMillis(300);

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

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(ODSAY_READ_TIMEOUT);
        OdSayApiAdapter odSayAdapter =
                new OdSayApiAdapter(new RestTemplate(factory), new ObjectMapper(), odSayProperties, metrics);

        TmapProperties tmapProperties = new TmapProperties();
        tmapProperties.setAppKey("test-key");
        tmapProperties.setBaseUrl(tmapServer.url("/").toString().replaceAll("/$", ""));
        tmapProperties.setRetryOnFailure(true);   // 운영 기본값 — 4xx 까지 재시도하는 현재 정책을 그대로 측정
        TmapPedestrianAdapter tmapAdapter =
                new TmapPedestrianAdapter(RestClient.builder().build(), tmapProperties, metrics);

        WalkSegmentCachePort walkCache = mock(WalkSegmentCachePort.class);
        when(walkCache.get(any())).thenReturn(Optional.empty());   // 항상 miss — provider 경로 측정

        WalkSegmentEnricher walkEnricher =
                new WalkSegmentEnricher(new WalkCoordResolver(), walkCache, tmapAdapter);
        RoutePolylineEnricher polylineEnricher = new RoutePolylineEnricher(
                mock(OdsayMapObjParser.class), mock(OdsayRoutePolylineSlicer.class),
                mock(OdsayRoutePolylineDataService.class));

        service = new RouteSearchService(
                odSayAdapter, tmapAdapter, mock(RouteDataService.class), new ObjectMapper(),
                polylineEnricher, walkEnricher, mock(TransferArrivalEnricher.class), metrics);
    }

    @AfterEach
    void tearDown() throws Exception {
        odsayServer.shutdown();
        tmapServer.shutdown();
    }

    // ── 시나리오 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("baseline 1 — 정상(ODsay 20ms, TMAP 20ms): 단계별 시간 분포")
    void baseline_normal() {
        odsayServer.setDispatcher(fixed(ODSAY_BODY, 20));
        tmapServer.setDispatcher(fixed(TMAP_BODY, 20));

        RouteSearchResult result = service.searchWithoutRealtime(request());

        assertThat(result.paths()).hasSize(1);
        report("정상(ODsay 20ms / TMAP 20ms)");
        assertThat(searchCount("full")).isEqualTo(1);
    }

    @Test
    @DisplayName("baseline 2 — TMAP 5s 지연: 선택 보강이 전체 응답을 그대로 지연시킨다(deadline 부재)")
    void baseline_slowTmap() {
        odsayServer.setDispatcher(fixed(ODSAY_BODY, 20));
        tmapServer.setDispatcher(fixed(TMAP_BODY, 5_000));

        long startNanos = System.nanoTime();
        service.searchWithoutRealtime(request());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        report("TMAP 5s 지연");
        System.out.printf("  전체 검색 소요: %,d ms%n", elapsedMs);

        // 핵심: 선택 보강(TMAP)의 지연이 잘려나가지 않고 응답에 그대로 실린다.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(5_000);
        assertThat(searchCount("full")).isEqualTo(1);
    }

    @Test
    @DisplayName("baseline 3 — TMAP 500: 기본 경로는 반환되지만 재시도 비용을 그대로 문다")
    void baseline_tmapServerError() {
        odsayServer.setDispatcher(fixed(ODSAY_BODY, 20));
        tmapServer.setDispatcher(status(500, 20));

        RouteSearchResult result = service.searchWithoutRealtime(request());

        report("TMAP 500(재시도 on)");

        // 선택 보강 실패 → 기본 경로 유지(부분 실패 허용은 이미 성립).
        assertThat(result.paths()).hasSize(1);
        assertThat(searchCount("base_only")).isEqualTo(1);
        assertThat(degradedCount("walk", "upstream_error")).isEqualTo(1);
        // 5xx 를 1회 재시도하므로 provider 호출은 2회 발생한다.
        assertThat(tmapServer.getRequestCount()).isEqualTo(2);
        assertThat(externalCallCount("tmap", "error", "true")).isEqualTo(1);
    }

    @Test
    @DisplayName("baseline 4 — ODsay 무응답: 3회 재시도 + 고정 backoff 6s 가 요청 스레드를 붙잡는다")
    void baseline_odsayTimeout() {
        odsayServer.setDispatcher(noResponse());
        tmapServer.setDispatcher(fixed(TMAP_BODY, 20));

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> service.searchWithoutRealtime(request()))
                .isInstanceOf(OdSayApiException.class);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        report("ODsay 무응답");
        System.out.printf("  전체 검색 소요: %,d ms (read timeout %d ms × 3회 + backoff 2s + 4s)%n",
                elapsedMs, ODSAY_READ_TIMEOUT.toMillis());

        // 필수 provider 실패 → FAILED. 재시도 3회 + backoff 6s 는 timeout 값과 무관하게 고정 비용이다.
        assertThat(odsayServer.getRequestCount()).isEqualTo(3);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(6_000);
        assertThat(searchCount("failed")).isEqualTo(1);
        assertThat(externalCallCount("odsay", "error", "true")).isEqualTo(1);
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

    private double externalCallCount(String provider, String outcome, String retry) {
        Counter counter = registry.find(RouteSearchMetrics.EXTERNAL_CALLS)
                .tag("provider", provider).tag("outcome", outcome).tag("retry", retry).counter();
        return counter == null ? 0 : counter.count();
    }

    /** RESULTS.md 원자료 — 단계별 소요와 provider 호출을 그대로 출력. */
    private void report(String scenario) {
        System.out.printf("%n=== baseline: %s ===%n", scenario);
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
