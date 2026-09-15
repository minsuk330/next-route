package watoo.grd.nextroute.application.route.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.route.dto.*;
import watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties;
import watoo.grd.nextroute.application.route.exception.OdSayApiException;
import watoo.grd.nextroute.application.route.exception.TmapApiException;
import watoo.grd.nextroute.application.route.observability.DegradeReason;
import watoo.grd.nextroute.application.route.observability.RouteSearchContext;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.observability.SearchComponent;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.port.out.TmapPedestrianPort;
import watoo.grd.nextroute.application.route.config.RoutingProviderProperties;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingPort;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingProviderSelector;
import watoo.grd.nextroute.application.route.routing.RoutingResult;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteSearchServiceTest {

    @Mock OdSayApiPort odSayApiPort;
    @Mock TmapPedestrianPort tmapPort;
    @Mock RoutePolylineEnricher polylineEnricher;
    @Mock WalkSegmentEnricher walkSegmentEnricher;
    @Mock TransferArrivalEnricher transferArrivalEnricher;
    @Mock OtpConcurrencyGate otpConcurrencyGate;

    RouteSearchService service;

    @BeforeEach
    void setUp() {
        // 기본 구성: provider=ODSAY, otp-enabled=false → OTP branch·gate 는 dormant.
        service = new RouteSearchService(
                odSayApiPort, tmapPort,
                polylineEnricher, walkSegmentEnricher, transferArrivalEnricher,
                new RouteSearchMetrics(new SimpleMeterRegistry()),
                new RouteSearchDeadlineProperties(),
                new RoutingProviderSelector(new RoutingProviderProperties(), java.util.List.of()),
                new LegacyRouteResponseMapper(mock(watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver.class)),
                new DepartureTimeResolver(Clock.systemDefaultZone()),
                otpConcurrencyGate,
                Clock.systemDefaultZone());
    }

    /** provider=OTP + otp-enabled 인 서비스. gate·mapper·otpPort 는 주입값 그대로 쓴다. */
    private RouteSearchService otpService(OtpConcurrencyGate gate, RoutingPort otpPort,
                                         LegacyRouteResponseMapper mapper) {
        RoutingProviderProperties props = new RoutingProviderProperties();
        props.setDefaultProvider(RoutingProvider.OTP);
        props.setOtpEnabled(true);
        return new RouteSearchService(
                odSayApiPort, tmapPort,
                polylineEnricher, walkSegmentEnricher, transferArrivalEnricher,
                new RouteSearchMetrics(new SimpleMeterRegistry()),
                new RouteSearchDeadlineProperties(),
                new RoutingProviderSelector(props, List.of(otpPort)),
                mapper,
                new DepartureTimeResolver(Clock.systemDefaultZone()),
                gate,
                Clock.systemDefaultZone());
    }

    private RoutingResult otpResult() {
        return new RoutingResult(RoutingProvider.OTP, "otp-2.9", "202503", "g-1", null, null,
                Instant.now(), List.of(), List.of());
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private SubPathResult walkSubPath(List<CoordPoint> polyline, List<WalkStep> steps) {
        return new SubPathResult(
                3, 240, 320.0,
                Collections.emptyList(), Collections.emptyList(),
                "출발", "도착",
                127.0, 37.5, 127.01, 37.51,
                null, null, null, null, null,
                polyline,
                null, null, null, null, null, null,
                steps,
                null, null, null, null, null, null, null
        );
    }

    private RouteSearchResult resultWith(List<SubPathResult> subPaths) {
        PathInfo info = new PathInfo(2700, 1400, 640, 1, "출발역", "도착역", null);
        return new RouteSearchResult(0, 0, 1, 0, 0,
                List.of(new PathResult(3, info, subPaths, Collections.emptyList())));
    }

    private RouteSearchRequest request() {
        return new RouteSearchRequest(127.0, 37.5, 127.01, 37.51, "출발지", "도착지");
    }

    // ── 테스트 ────────────────────────────────────────────────────────────

    @Test
    void TC_정상_검색_흐름_enricher_호출순서() {
        RouteSearchResult odsayResult = resultWith(List.of(walkSubPath(null, null)));
        RouteSearchResult afterPolyline = resultWith(List.of(walkSubPath(null, null)));
        RouteSearchResult afterWalk = resultWith(List.of(
                walkSubPath(List.of(new CoordPoint(127.0, 37.5)),
                        List.of(new WalkStep(0, "SP", 200, "이동", 127.0, 37.5)))));
        RouteSearchResult afterTransfer = afterWalk;

        when(odSayApiPort.searchPath(eq(127.0), eq(37.5), eq(127.01), eq(37.51), anyLong())).thenReturn(odsayResult);
        when(polylineEnricher.enrich(eq(odsayResult), any(RouteSearchContext.class))).thenReturn(afterPolyline);
        when(walkSegmentEnricher.enrich(eq(afterPolyline), eq(127.0), eq(37.5), eq("출발지"),
                eq(127.01), eq(37.51), eq("도착지"), any(RouteSearchContext.class),
                any(RouteSearchDeadlineProperties.class))).thenReturn(afterWalk);
        when(transferArrivalEnricher.enrich(eq(afterWalk), any(Instant.class), any(RouteSearchContext.class)))
                .thenReturn(afterTransfer);

        RouteSearchResult result = service.search(request());

        var order = inOrder(odSayApiPort, polylineEnricher, walkSegmentEnricher, transferArrivalEnricher);
        order.verify(odSayApiPort).searchPath(eq(127.0), eq(37.5), eq(127.01), eq(37.51), anyLong());
        order.verify(polylineEnricher).enrich(eq(odsayResult), any(RouteSearchContext.class));
        order.verify(walkSegmentEnricher).enrich(any(), eq(127.0), eq(37.5), eq("출발지"),
                eq(127.01), eq(37.51), eq("도착지"), any(RouteSearchContext.class),
                any(RouteSearchDeadlineProperties.class));
        order.verify(transferArrivalEnricher).enrich(eq(afterWalk), any(Instant.class), any(RouteSearchContext.class));

        // withMeta 로 감싸므로 인스턴스는 다르지만 보강 결과(paths)는 그대로 전달된다.
        assertThat(result.paths()).isSameAs(afterTransfer.paths());
        assertThat(result.meta()).isNotNull();
        assertThat(result.meta().outcome()).isEqualTo("full");
        assertThat(result.meta().degraded()).isEmpty();
        SubPathResult walk = result.paths().get(0).subPaths().get(0);
        assertThat(walk.polyline()).hasSize(1);
        assertThat(walk.walkSteps()).hasSize(1);
    }

    // ── -98(700m 이내) TMAP 도보 폴백 ────────────────────────────────────

    @Test
    void TC_700m이내_98_TMAP_도보경로로만_응답() {
        when(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .thenThrow(new OdSayApiException(-98, "출, 도착지가 700m이내입니다."));
        WalkSegment segment = new WalkSegment(
                List.of(new CoordPoint(127.0, 37.5), new CoordPoint(127.01, 37.51)),
                420, 360,
                List.of(new WalkStep(0, "SP", 200, "이동", 127.0, 37.5)));
        when(tmapPort.search(any())).thenReturn(segment);

        RouteSearchResult result = service.search(request());

        assertThat(result.paths()).hasSize(1);
        PathResult path = result.paths().get(0);
        assertThat(path.pathType()).isEqualTo(3);
        assertThat(path.subPaths()).hasSize(1);
        SubPathResult walk = path.subPaths().get(0);
        assertThat(walk.trafficType()).isEqualTo(3);
        assertThat(walk.distance()).isEqualTo(420.0);
        assertThat(walk.sectionTime()).isEqualTo(6);          // 360s → 6분
        assertThat(walk.walkTotalTimeSeconds()).isEqualTo(360);
        assertThat(walk.polyline()).hasSize(2);
        assertThat(walk.walkSteps()).hasSize(1);
        assertThat(path.info().totalWalk()).isEqualTo(420);

        // 폴백 경로는 enricher 체인을 타지 않음
        verifyNoInteractions(polylineEnricher, walkSegmentEnricher, transferArrivalEnricher);
    }

    @Test
    void TC_700m이내_98_TMAP_빈응답이면_원래_98_에러_전파() {
        when(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .thenThrow(new OdSayApiException(-98, "출, 도착지가 700m이내입니다."));
        when(tmapPort.search(any())).thenReturn(WalkSegment.empty());

        assertThatThrownBy(() -> service.search(request()))
                .isInstanceOf(OdSayApiException.class)
                .satisfies(e -> assertThat(((OdSayApiException) e).getErrorCode()).isEqualTo(-98));
    }

    @Test
    void TC_700m이내_98_TMAP_호출실패면_원래_98_에러_전파() {
        when(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .thenThrow(new OdSayApiException(-98, "출, 도착지가 700m이내입니다."));
        when(tmapPort.search(any())).thenThrow(new TmapApiException(500, "5xx"));

        assertThatThrownBy(() -> service.search(request()))
                .isInstanceOf(OdSayApiException.class)
                .satisfies(e -> assertThat(((OdSayApiException) e).getErrorCode()).isEqualTo(-98));
    }

    @Test
    void TC_98이_아닌_ODsay_에러는_그대로_전파_TMAP_미호출() {
        when(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .thenThrow(new OdSayApiException(-99, "다른 에러"));

        assertThatThrownBy(() -> service.search(request()))
                .isInstanceOf(OdSayApiException.class)
                .satisfies(e -> assertThat(((OdSayApiException) e).getErrorCode()).isEqualTo(-99));
        verifyNoInteractions(tmapPort);
    }

    // ── Step 5: 응답 완전성 계약(meta) ──────────────────────────────────────

    @Test
    void TC_meta_선택보강_일부만_실패시_PARTIAL_과_degraded_목록() {
        RouteSearchResult odsay = resultWith(List.of(walkSubPath(null, null)));
        when(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .thenReturn(odsay);
        when(polylineEnricher.enrich(any(), any())).thenReturn(odsay);
        // WALK 성공(적용됨), BUS_REALTIME timeout 실패 → 적용 2 / 실패 1 = PARTIAL
        when(walkSegmentEnricher.enrich(any(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), any(RouteSearchContext.class), any()))
                .thenAnswer(inv -> {
                    inv.getArgument(7, RouteSearchContext.class).requested(SearchComponent.WALK);
                    return odsay;
                });
        when(transferArrivalEnricher.enrich(any(), any(), any(RouteSearchContext.class)))
                .thenAnswer(inv -> {
                    inv.getArgument(2, RouteSearchContext.class)
                            .degrade(SearchComponent.BUS_REALTIME, DegradeReason.TIMEOUT);
                    return odsay;
                });

        RouteSearchResult result = service.search(request());

        assertThat(result.meta().outcome()).isEqualTo("partial");
        assertThat(result.meta().degraded())
                .containsExactly(new SearchMeta.DegradedComponent("bus_realtime", "timeout"));
    }

    @Test
    void TC_meta_적용가능_보강이_전부_실패하면_BASE_ONLY() {
        RouteSearchResult odsay = resultWith(List.of(walkSubPath(null, null)));
        when(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .thenReturn(odsay);
        when(polylineEnricher.enrich(any(), any())).thenReturn(odsay);
        when(walkSegmentEnricher.enrich(any(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), any(RouteSearchContext.class), any()))
                .thenAnswer(inv -> {
                    inv.getArgument(7, RouteSearchContext.class)
                            .degrade(SearchComponent.WALK, DegradeReason.BULKHEAD_FULL);
                    return odsay;
                });
        when(transferArrivalEnricher.enrich(any(), any(), any())).thenReturn(odsay);

        RouteSearchResult result = service.search(request());

        assertThat(result.meta().outcome()).isEqualTo("base_only");
        assertThat(result.meta().degraded())
                .containsExactly(new SearchMeta.DegradedComponent("walk", "bulkhead_full"));
    }

    @Test
    void TC_meta_700m_도보폴백도_meta_full_로_반환() {
        when(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .thenThrow(new OdSayApiException(-98, "출, 도착지가 700m이내입니다."));
        when(tmapPort.search(any())).thenReturn(new WalkSegment(
                List.of(new CoordPoint(127.0, 37.5), new CoordPoint(127.01, 37.51)),
                420, 360, List.of(new WalkStep(0, "SP", 200, "이동", 127.0, 37.5))));

        RouteSearchResult result = service.search(request());

        assertThat(result.meta().outcome()).isEqualTo("full");
        assertThat(result.meta().degraded()).isEmpty();
    }

    // ── OTP 동시성 gate 배선 ────────────────────────────────────────────────

    @Test
    void TC_OTP선택_permit있으면_OTP경로_ODSAY미호출_release됨() {
        RoutingPort otpPort = mock(RoutingPort.class);
        when(otpPort.provider()).thenReturn(RoutingProvider.OTP);
        when(otpPort.search(any(), any())).thenReturn(otpResult());
        LegacyRouteResponseMapper mapper = mock(LegacyRouteResponseMapper.class);
        RouteSearchResult mapped = resultWith(List.of(walkSubPath(null, null)));
        when(mapper.toLegacy(any(RoutingResult.class))).thenReturn(mapped);

        OtpPermit permit = new OtpPermit("tok-1");
        when(otpConcurrencyGate.tryAcquire(any(RoutingDeadline.class), eq("api")))
                .thenReturn(Optional.of(permit));

        RouteSearchService otp = otpService(otpConcurrencyGate, otpPort, mapper);
        RouteSearchResult result = otp.search(request());

        assertThat(result.paths()).isNotEmpty();
        verify(otpPort).search(any(), any());
        verify(otpConcurrencyGate).release(permit);   // finally 반납
        verifyNoInteractions(odSayApiPort);           // provider 안 섞음
    }

    @Test
    void TC_OTP선택_permit없으면_504_timeout_OTP도ODSAY도_미호출() {
        RoutingPort otpPort = mock(RoutingPort.class);
        when(otpPort.provider()).thenReturn(RoutingProvider.OTP);
        LegacyRouteResponseMapper mapper = mock(LegacyRouteResponseMapper.class);

        when(otpConcurrencyGate.tryAcquire(any(RoutingDeadline.class), eq("api")))
                .thenReturn(Optional.empty());   // 포화·Redis fail-closed

        RouteSearchService otp = otpService(otpConcurrencyGate, otpPort, mapper);

        assertThatThrownBy(() -> otp.search(request()))
                .isInstanceOf(OdSayApiException.class)
                .satisfies(e -> assertThat(((OdSayApiException) e).getKind())
                        .isEqualTo(OdSayApiException.Kind.TIMEOUT));
        verify(otpPort, never()).search(any(), any());
        verify(otpConcurrencyGate, never()).release(any());
        verifyNoInteractions(odSayApiPort);           // ODSAY 폴백 없음
    }

    @Test
    void TC_기본구성_otpEnabled_false_는_gate_안타고_ODSAY() {
        RouteSearchResult odsay = resultWith(List.of(walkSubPath(null, null)));
        when(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .thenReturn(odsay);
        when(polylineEnricher.enrich(any(), any())).thenReturn(odsay);
        when(walkSegmentEnricher.enrich(any(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), any(), any())).thenReturn(odsay);
        when(transferArrivalEnricher.enrich(any(), any(), any())).thenReturn(odsay);

        service.search(request());   // setUp 의 기본 구성(ODSAY)

        verify(odSayApiPort).searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong());
        verifyNoInteractions(otpConcurrencyGate);   // 회귀 0: gate dormant
    }

}
