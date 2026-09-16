package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.application.route.routing.RoutingCacheContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.port.out.RemainingRouteCachePort;
import watoo.grd.nextroute.application.journey.service.JourneyWaypointResolver.Coord;
import watoo.grd.nextroute.application.journey.service.JourneyWaypointResolver.Waypoints;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;
import watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties;
import watoo.grd.nextroute.application.route.config.RoutingProviderProperties;
import watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingPort;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingProviderSelector;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.service.LegacyRouteResponseMapper;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 남은 구간 경로 검색. <b>여기서만 유료 호출이 나간다</b>는 것이 이 클래스의 전부다.
 *
 * <p>그래서 못박는 것도 요금 이야기다 — 캐시가 맞으면 부르지 않고, 동시에 같은 질문이 오면 한 번만
 * 부르고, 무엇이 실패하든 예외 대신 빈 목록을 낸다.
 */
@DisplayName("남은 구간 경로 검색")
class RemainingRouteSearchServiceTest {

    private static final String STOP_ID = "ST-C";
    private static final Waypoints WAYPOINTS =
            new Waypoints(new Coord(127.3, 37.3), new Coord(127.5, 37.5));

    private final RemainingRouteCachePort cache = mock(RemainingRouteCachePort.class);
    private final OdSayApiPort odSayApiPort = mock(OdSayApiPort.class);
    private final JourneyRerouteMetrics metrics =
            new JourneyRerouteMetrics(new SimpleMeterRegistry());
    private final OtpConcurrencyGate otpConcurrencyGate = mock(OtpConcurrencyGate.class);
    // 기본 구성: provider=ODSAY → gate dormant(loadOtpGated 미진입).
    private final RemainingRouteSearchService service =
            new RemainingRouteSearchService(cache, odSayApiPort, metrics,
                    new RoutingProviderSelector(new RoutingProviderProperties(), java.util.List.of()),
                    new LegacyRouteResponseMapper(mock(OtpSeoulIdentityResolver.class)),
                    new OtpRoutingProperties(),
                    otpConcurrencyGate,
                    new RouteSearchDeadlineProperties(),
                    Clock.systemDefaultZone());

    /** provider=OTP 인 재탐색 서비스. gate·otpPort·mapper 는 주입값 그대로. */
    private RemainingRouteSearchService otpService(OtpConcurrencyGate gate, RoutingPort otpPort,
                                                  LegacyRouteResponseMapper mapper) {
        RoutingProviderProperties props = new RoutingProviderProperties();
        props.setDefaultProvider(RoutingProvider.OTP);
        props.setOtpEnabled(true);
        return new RemainingRouteSearchService(cache, odSayApiPort, metrics,
                new RoutingProviderSelector(props, List.of(otpPort)),
                mapper, new OtpRoutingProperties(), gate, new RouteSearchDeadlineProperties(),
                Clock.systemDefaultZone());
    }

    private RoutingResult otpResult() {
        return new RoutingResult(RoutingProvider.OTP, "otp-2.9", "202503", "g-1", null, null,
                Instant.now(), List.of(), List.of());
    }

    @Test
    @DisplayName("캐시가 맞으면 검색을 부르지 않는다")
    void cacheHitSkipsSearch() {
        given(cache.find(any(RoutingCacheContext.class), eq(STOP_ID), eq(127.5), eq(37.5))).willReturn(Optional.of(List.of(path())));

        assertThat(service.search(STOP_ID, WAYPOINTS, 2000)).hasSize(1);
        verifyNoInteractions(odSayApiPort);
    }

    @Test
    @DisplayName("캐시가 비면 검색하고 그 결과를 캐시에 넣는다")
    void cacheMissCallsSearchAndStores() {
        given(cache.find(any(RoutingCacheContext.class), eq(STOP_ID), eq(127.5), eq(37.5))).willReturn(Optional.empty());
        given(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .willReturn(searchResult(path()));

        List<PathResult> paths = service.search(STOP_ID, WAYPOINTS, 2000);

        assertThat(paths).hasSize(1);
        verify(odSayApiPort).searchPath(eq(127.3), eq(37.3), eq(127.5), eq(37.5), eq(2000L));
        verify(cache).put(any(RoutingCacheContext.class), eq(STOP_ID), eq(127.5), eq(37.5), eq(paths));
    }

    @Test
    @DisplayName("검색이 빈 답을 주면 캐시에 넣지 않는다 — 10분간 빈손이 된다")
    void emptyResultIsNotCached() {
        given(cache.find(any(RoutingCacheContext.class), eq(STOP_ID), eq(127.5), eq(37.5))).willReturn(Optional.empty());
        given(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .willReturn(searchResult());

        assertThat(service.search(STOP_ID, WAYPOINTS, 2000)).isEmpty();
        verify(cache, never()).put(any(RoutingCacheContext.class), eq(STOP_ID), anyDouble(), anyDouble(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("검색이 터지면 예외 대신 빈 목록이다")
    void searchFailureIsEmptyList() {
        given(cache.find(any(RoutingCacheContext.class), eq(STOP_ID), eq(127.5), eq(37.5))).willReturn(Optional.empty());
        willThrow(new IllegalStateException("odsay down")).given(odSayApiPort)
                .searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong());

        assertThat(service.search(STOP_ID, WAYPOINTS, 2000)).isEmpty();
    }

    @Test
    @DisplayName("캐시가 죽어도 검색은 그대로 나간다 — 캐시는 fail-open 이다")
    void cacheFailureStillSearches() {
        given(cache.find(any(RoutingCacheContext.class), eq(STOP_ID), eq(127.5), eq(37.5))).willReturn(Optional.empty());
        willThrow(new IllegalStateException("redis down")).given(cache)
                .put(any(RoutingCacheContext.class), eq(STOP_ID), anyDouble(), anyDouble(), org.mockito.ArgumentMatchers.anyList());
        given(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .willReturn(searchResult(path()));

        // 캐시에 못 넣었다고 이미 돈을 내고 받아 온 경로를 버리면 Redis 장애가 곧 "제안 없음"이 된다.
        assertThat(service.search(STOP_ID, WAYPOINTS, 2000)).hasSize(1);
    }

    @Test
    @DisplayName("동시에 같은 질문이 오면 검색은 한 번만 나간다")
    void concurrentRequestsCallSearchOnce() throws Exception {
        given(cache.find(any(RoutingCacheContext.class), eq(STOP_ID), eq(127.5), eq(37.5))).willReturn(Optional.empty());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        given(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .willAnswer(invocation -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    release.await(2, TimeUnit.SECONDS);
                    return searchResult(path());
                });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<PathResult>> owner = pool.submit(() -> service.search(STOP_ID, WAYPOINTS, 2000));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<List<PathResult>> joiner = pool.submit(() -> service.search(STOP_ID, WAYPOINTS, 2000));
            Thread.sleep(50);
            release.countDown();

            assertThat(owner.get(3, TimeUnit.SECONDS)).hasSize(1);
            assertThat(joiner.get(3, TimeUnit.SECONDS)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(calls.get()).isEqualTo(1);
        verify(odSayApiPort, times(1))
                .searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong());
    }

    @Test
    @DisplayName("OTP 포화면 OTP 를 부르지 않고 빈 목록이다 — ODSAY 로 섞지 않는다")
    void otpSaturatedReturnsEmpty() {
        RoutingPort otpPort = mock(RoutingPort.class);
        given(otpPort.provider()).willReturn(RoutingProvider.OTP);
        LegacyRouteResponseMapper mapper = mock(LegacyRouteResponseMapper.class);
        RemainingRouteSearchService otp = otpService(otpConcurrencyGate, otpPort, mapper);

        given(cache.find(any(RoutingCacheContext.class), eq(STOP_ID), eq(127.5), eq(37.5)))
                .willReturn(Optional.empty());
        given(otpConcurrencyGate.tryAcquire(any(RoutingDeadline.class), eq("worker")))
                .willReturn(Optional.empty());   // 포화·fail-closed

        assertThat(otp.search(STOP_ID, WAYPOINTS, 3000)).isEmpty();
        verify(otpPort, never()).search(any(), any());
        verify(otpConcurrencyGate, never()).release(any());
        verifyNoInteractions(odSayApiPort);
    }

    @Test
    @DisplayName("gate 대기와 OTP 호출이 같은 deadline 을 공유한다 — 예산 이중소비 없음, release 됨")
    void otpSharesOneDeadlineAndReleases() {
        RoutingPort otpPort = mock(RoutingPort.class);
        given(otpPort.provider()).willReturn(RoutingProvider.OTP);
        given(otpPort.search(any(), any())).willReturn(otpResult());
        LegacyRouteResponseMapper mapper = mock(LegacyRouteResponseMapper.class);
        given(mapper.toLegacy(any(RoutingResult.class))).willReturn(searchResult(path()));
        RemainingRouteSearchService otp = otpService(otpConcurrencyGate, otpPort, mapper);

        given(cache.find(any(RoutingCacheContext.class), eq(STOP_ID), eq(127.5), eq(37.5)))
                .willReturn(Optional.empty());
        OtpPermit permit = new OtpPermit("tok-w");
        ArgumentCaptor<RoutingDeadline> gateDeadline = ArgumentCaptor.forClass(RoutingDeadline.class);
        given(otpConcurrencyGate.tryAcquire(gateDeadline.capture(), eq("worker")))
                .willReturn(Optional.of(permit));

        assertThat(otp.search(STOP_ID, WAYPOINTS, 3000)).hasSize(1);

        ArgumentCaptor<RoutingDeadline> searchDeadline = ArgumentCaptor.forClass(RoutingDeadline.class);
        verify(otpPort).search(any(), searchDeadline.capture());
        // 같은 인스턴스 = 대기 후 남은 예산으로 호출(단계별 새 예산 발급 아님).
        assertThat(searchDeadline.getValue()).isSameAs(gateDeadline.getValue());
        // OTP 호출 예산 = otpMs(기본 3500), 넘긴 budgetMs(3000) 아님(단일소스).
        long remaining = searchDeadline.getValue().remainingMillis(Clock.systemDefaultZone());
        assertThat(remaining).isGreaterThan(3000L).isLessThanOrEqualTo(3500L);
        verify(otpConcurrencyGate).release(permit);
    }

    private RouteSearchResult searchResult(PathResult... paths) {
        return new RouteSearchResult(0, 0, 0, 0, 0, List.of(paths));
    }

    private PathResult path() {
        SubPathResult sub = new SubPathResult(2, 15, null, null, null,
                "정류장C", "정류장D", 127.3, 37.3, 127.5, 37.5,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                "ST-C", "ST-D", null, null, null, null, null);
        return new PathResult(1, null, List.of(sub), List.of());
    }
}
