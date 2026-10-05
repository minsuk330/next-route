package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.service.JourneyWaypointResolver.Coord;
import watoo.grd.nextroute.application.journey.service.JourneyWaypointResolver.Waypoints;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties;
import watoo.grd.nextroute.application.route.config.RoutingProviderProperties;
import watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingPort;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingProviderSelector;
import watoo.grd.nextroute.application.route.routing.RoutingQuery;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.service.LegacyRouteResponseMapper;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 남은 구간 경로 검색. <b>여기서만 유료 호출이 나간다</b>는 것이 이 클래스의 전부다.
 *
 * <p>캐시를 두지 않는다(재탐색은 계약상 실시간). 그래서 못박는 것은 매 재탐색이 <b>그때 상태로
 * 직접 provider 를 부른다</b>는 것, 무엇이 실패하든 예외 대신 빈 목록을 낸다는 것, OTP 과부하는
 * 캐시가 아니라 동시성 gate 로 막는다는 것이다.
 */
@DisplayName("남은 구간 경로 검색")
class RemainingRouteSearchServiceTest {

    /** ODSAY 롤백 구성. 기본값에 기대지 않고 명시한다 — P7 부터 코드 기본값은 OTP 다. */
    private static RoutingProviderProperties odsayProps() {
        RoutingProviderProperties props = new RoutingProviderProperties();
        props.setDefaultProvider(RoutingProvider.ODSAY);
        return props;
    }


    private static final String STOP_ID = "ST-C";
    private static final Waypoints WAYPOINTS =
            new Waypoints(new Coord(127.3, 37.3), new Coord(127.5, 37.5));

    private final OdSayApiPort odSayApiPort = mock(OdSayApiPort.class);
    private final JourneyRerouteMetrics metrics =
            new JourneyRerouteMetrics(new SimpleMeterRegistry());
    private final OtpConcurrencyGate otpConcurrencyGate = mock(OtpConcurrencyGate.class);
    // 기본 구성: provider=ODSAY → gate dormant(loadOtpGated 미진입).
    private final RemainingRouteSearchService service =
            new RemainingRouteSearchService(odSayApiPort, metrics,
                    new RoutingProviderSelector(odsayProps(), java.util.List.of()),
                    new LegacyRouteResponseMapper(mock(OtpSeoulIdentityResolver.class)),
                    otpConcurrencyGate,
                    new RouteSearchDeadlineProperties(),
                    Clock.systemDefaultZone());

    /** provider=OTP 인 재탐색 서비스. gate·otpPort·mapper 는 주입값 그대로. */
    private RemainingRouteSearchService otpService(OtpConcurrencyGate gate, RoutingPort otpPort,
                                                  LegacyRouteResponseMapper mapper) {
        RoutingProviderProperties props = new RoutingProviderProperties();
        props.setDefaultProvider(RoutingProvider.OTP);
        props.setOtpEnabled(true);
        return new RemainingRouteSearchService(odSayApiPort, metrics,
                new RoutingProviderSelector(props, List.of(otpPort)),
                mapper, gate, new RouteSearchDeadlineProperties(),
                Clock.systemDefaultZone());
    }

    private RoutingResult otpResult() {
        return new RoutingResult(RoutingProvider.OTP, "otp-2.9", "202503", "g-1", null, null,
                Instant.now(), List.of(), List.of());
    }

    @Test
    @DisplayName("provider 를 직접 부르고 그 결과를 낸다")
    void callsProvider() {
        given(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .willReturn(searchResult(path()));

        List<PathResult> paths = service.search(STOP_ID, WAYPOINTS, 2000);

        assertThat(paths).hasSize(1);
        verify(odSayApiPort).searchPath(eq(127.3), eq(37.3), eq(127.5), eq(37.5), eq(2000L));
    }

    @Test
    @DisplayName("검색이 빈 답을 주면 빈 목록이다")
    void emptyResultReturnsEmpty() {
        given(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .willReturn(searchResult());

        assertThat(service.search(STOP_ID, WAYPOINTS, 2000)).isEmpty();
    }

    @Test
    @DisplayName("검색이 터지면 예외 대신 빈 목록이다")
    void searchFailureIsEmptyList() {
        willThrow(new IllegalStateException("odsay down")).given(odSayApiPort)
                .searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong());

        assertThat(service.search(STOP_ID, WAYPOINTS, 2000)).isEmpty();
    }

    @Test
    @DisplayName("OTP 포화면 OTP 를 부르지 않고 빈 목록이다 — ODSAY 로 섞지 않는다")
    void otpSaturatedReturnsEmpty() {
        RoutingPort otpPort = mock(RoutingPort.class);
        given(otpPort.provider()).willReturn(RoutingProvider.OTP);
        LegacyRouteResponseMapper mapper = mock(LegacyRouteResponseMapper.class);
        RemainingRouteSearchService otp = otpService(otpConcurrencyGate, otpPort, mapper);

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

        OtpPermit permit = new OtpPermit("tok-w");
        ArgumentCaptor<RoutingDeadline> gateDeadline = ArgumentCaptor.forClass(RoutingDeadline.class);
        given(otpConcurrencyGate.tryAcquire(gateDeadline.capture(), eq("worker")))
                .willReturn(Optional.of(permit));

        assertThat(otp.search(STOP_ID, WAYPOINTS, 3000)).hasSize(1);

        ArgumentCaptor<RoutingDeadline> searchDeadline = ArgumentCaptor.forClass(RoutingDeadline.class);
        verify(otpPort).search(any(), searchDeadline.capture());
        // 같은 인스턴스 = 대기 후 남은 예산으로 호출(단계별 새 예산 발급 아님).
        assertThat(searchDeadline.getValue()).isSameAs(gateDeadline.getValue());
        // OTP 호출 예산 = otpMs(기본 8000), 넘긴 budgetMs(3000) 아님(단일소스).
        long remaining = searchDeadline.getValue().remainingMillis(Clock.systemDefaultZone());
        assertThat(remaining).isGreaterThan(3000L).isLessThanOrEqualTo(8000L);
        verify(otpConcurrencyGate).release(permit);
    }

    @Test
    @DisplayName("원점 도달 시각으로 검색한다 — 지금이 아니다(PR-2)")
    void otpDepartsAtGivenInstant() {
        RoutingPort otpPort = mock(RoutingPort.class);
        given(otpPort.provider()).willReturn(RoutingProvider.OTP);
        given(otpPort.search(any(), any())).willReturn(otpResult());
        LegacyRouteResponseMapper mapper = mock(LegacyRouteResponseMapper.class);
        given(mapper.toLegacy(any(RoutingResult.class))).willReturn(searchResult(path()));
        RemainingRouteSearchService otp = otpService(otpConcurrencyGate, otpPort, mapper);
        given(otpConcurrencyGate.tryAcquire(any(RoutingDeadline.class), eq("worker")))
                .willReturn(Optional.of(new OtpPermit("tok-w")));

        Instant departAt = Instant.now().plusSeconds(600);
        assertThat(otp.search(STOP_ID, WAYPOINTS, 3000, departAt)).hasSize(1);

        ArgumentCaptor<RoutingQuery> query = ArgumentCaptor.forClass(RoutingQuery.class);
        verify(otpPort).search(query.capture(), any());
        assertThat(query.getValue().departureAt()).isEqualTo(departAt);
    }

    @Test
    @DisplayName("이미 지난 출발 시각은 현재 시각으로 되돌린다 — 지나간 편이 답으로 나온다")
    void pastDepartureFallsBackToNow() {
        RoutingPort otpPort = mock(RoutingPort.class);
        given(otpPort.provider()).willReturn(RoutingProvider.OTP);
        given(otpPort.search(any(), any())).willReturn(otpResult());
        LegacyRouteResponseMapper mapper = mock(LegacyRouteResponseMapper.class);
        given(mapper.toLegacy(any(RoutingResult.class))).willReturn(searchResult(path()));
        RemainingRouteSearchService otp = otpService(otpConcurrencyGate, otpPort, mapper);
        given(otpConcurrencyGate.tryAcquire(any(RoutingDeadline.class), eq("worker")))
                .willReturn(Optional.of(new OtpPermit("tok-w")));

        Instant before = Instant.now();
        otp.search(STOP_ID, WAYPOINTS, 3000, before.minusSeconds(600));

        ArgumentCaptor<RoutingQuery> query = ArgumentCaptor.forClass(RoutingQuery.class);
        verify(otpPort).search(query.capture(), any());
        assertThat(query.getValue().departureAt()).isAfterOrEqualTo(before);
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
