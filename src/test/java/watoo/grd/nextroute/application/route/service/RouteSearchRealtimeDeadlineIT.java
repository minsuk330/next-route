package watoo.grd.nextroute.application.route.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import watoo.grd.nextroute.application.route.port.out.CrossInstanceFlightPort;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.bus.port.out.BusApiBreakerPort;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheReadPort;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheWritePort;
import watoo.grd.nextroute.application.route.config.MlPredictorProperties;
import watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties;
import watoo.grd.nextroute.application.route.config.SearchTimeBusProperties;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.dto.*;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.*;
import watoo.grd.nextroute.infrastructure.adapter.out.api.bus.CachedSearchTimeBusAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.api.bus.SearchTimeBusAdapter;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 전체 search() 경로에서 버스 provider 무응답이 요청 deadline을 넘기지 않는지 검증한다. */
class RouteSearchRealtimeDeadlineIT {

    /** ODSAY 롤백 구성. 기본값에 기대지 않고 명시한다 — P7 부터 코드 기본값은 OTP 다. */
    private static watoo.grd.nextroute.application.route.config.RoutingProviderProperties odsayProps() {
        var props = new watoo.grd.nextroute.application.route.config.RoutingProviderProperties();
        props.setDefaultProvider(watoo.grd.nextroute.application.route.routing.RoutingProvider.ODSAY);
        return props;
    }


    private MockWebServer busServer;

    @BeforeEach
    void setUp() throws Exception {
        busServer = new MockWebServer();
        busServer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        busServer.shutdown();
    }

    @Test
    void TC_버스_API_무응답도_전체_search_deadline_안에_기본경로_반환() {
        busServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        RouteSearchMetrics metrics = new RouteSearchMetrics(new SimpleMeterRegistry());
        TransferArrivalProperties transferProps = new TransferArrivalProperties();
        transferProps.setEnabled(true);
        transferProps.setDeadlineMs(1_500);
        transferProps.setExternalCallAcquireMs(20);

        BusApiBreakerPort breaker = mock(BusApiBreakerPort.class);
        BusApiQuotaPort quota = mock(BusApiQuotaPort.class);
        when(breaker.getBlockedUntil(any())).thenReturn(Optional.empty());
        when(quota.tryAcquireSearch(any())).thenReturn(true);

        SearchTimeBusAdapter provider = new SearchTimeBusAdapter(
                RestClient.builder().build(), breaker, quota, new Semaphore(1), transferProps,
                Clock.systemUTC(),
                new watoo.grd.nextroute.common.config.JdkHttpClientMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                "test-key", busServer.url("/").toString().replaceAll("/$", ""));
        BusRealtimeCacheReadPort cacheRead = mock(BusRealtimeCacheReadPort.class);
        when(cacheRead.getArrivals(any(), any(), anyInt())).thenReturn(Optional.empty());
        CachedSearchTimeBusAdapter busPort = new CachedSearchTimeBusAdapter(
                provider, cacheRead, mock(BusRealtimeCacheWritePort.class), metrics,
                new SearchTimeBusProperties(), CrossInstanceFlightPort.none());

        TransferStopResolver resolver = mock(TransferStopResolver.class);
        when(resolver.resolveStop(any())).thenReturn(Optional.of(
                new TransferStopResolver.StopResolution("stop-1")));
        when(resolver.resolveRoute(any(), any())).thenReturn(Optional.of(
                new TransferStopResolver.RouteResolution("route-1", "360", false, 10)));
        when(resolver.resolveSeq(any(), any())).thenReturn(
                new TransferStopResolver.SeqResolution(List.of(3)));

        MlPredictorProperties mlProps = new MlPredictorProperties();
        mlProps.setEnabled(false);
        TransferArrivalEnricher transferEnricher = new TransferArrivalEnricher(
                transferProps, mlProps, busPort, mock(MlArrivalPredictorPort.class), resolver,
                mock(MlFeatureVectorBuilder.class), mock(PredictionSupportService.class), Clock.systemUTC());

        RouteSearchResult base = busRouteResult();
        OdSayApiPort odsay = mock(OdSayApiPort.class);
        when(odsay.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .thenReturn(base);
        WalkSegmentEnricher walk = mock(WalkSegmentEnricher.class);
        when(walk.enrich(any(), anyDouble(), anyDouble(), any(), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(base);

        RouteSearchDeadlineProperties deadline = new RouteSearchDeadlineProperties();
        deadline.setDeadlineMs(250);
        deadline.setOdsayMs(100);
        deadline.setTmapMs(100);
        RouteSearchService service = new RouteSearchService(
                odsay, mock(TmapPedestrianPort.class),
                walk, transferEnricher, metrics, deadline,
                new watoo.grd.nextroute.application.route.routing.RoutingProviderSelector(
                        odsayProps(), java.util.List.of()),
                new LegacyRouteResponseMapper(mock(watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver.class)),
                new DepartureTimeResolver(java.time.Clock.systemDefaultZone()),
                mock(watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate.class),
                java.time.Clock.systemDefaultZone(), new watoo.grd.nextroute.application.route.config.OtpRoutingProperties(),
                new WalkCoordResolver(),
                new watoo.grd.nextroute.application.route.routing.ExpressFilterPolicy(
                        watoo.grd.nextroute.application.subway.service.SubwayExpressRouteFixtures.railServiceClassifier()));

        long started = System.nanoTime();
        RouteSearchResult result = service.search(
                new RouteSearchRequest(127.0, 37.5, 127.01, 37.51, "출발", "도착"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMs).isLessThan(600);
        assertThat(result.paths()).hasSize(1);
        assertThat(result.paths().get(0).subPaths().get(0).transferArrivals().get(0).status())
                .isEqualTo(TransferArrival.Status.ERROR);
    }

    private RouteSearchResult busRouteResult() {
        SubPathResult bus = new SubPathResult(
                2, 10, 1_500.0,
                List.of(new LaneResult("360", "360", null, 1, null, "route-1", null)),
                Collections.emptyList(), "출발", "도착",
                127.0, 37.5, 127.01, 37.51,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, "stop-1", null, null, null, null, null, null, null, null, null, null);
        PathInfo info = new PathInfo(10, 0, 0, 0, "출발", "도착", null);
        return new RouteSearchResult(0, 1, 0, 0, 0,
                List.of(new PathResult(2, info, List.of(bus), List.of())));
    }
}
