package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.dto.OtpRailRoute;
import watoo.grd.nextroute.application.subway.dto.RailPattern;
import watoo.grd.nextroute.application.subway.port.out.SubwayRoutePatternPort;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 노선형 급행 패턴(subway-express PR-S4): 노선 고르기·방향 라벨·캐시 키·워밍업. */
@DisplayName("급행 노선 패턴")
class SubwayExpressPatternsTest {

    private static List<String> ends(String lineId, String direction) {
        return SubwayPatternFixtures.expressPatterns(lineId, direction).stream()
                .filter(p -> p.tripCount() >= 50)
                .map(p -> p.stops().get(0).name() + "→" + p.stops().get(p.stops().size() - 1).name())
                .toList();
    }

    @Test
    @DisplayName("9호선 급행은 라벨이 반대(09-2D = 실시간 상행), 1호선 경인·경부 급행(01-4·01-6)은 그대로 — 운영 fixture 역 순서로 확인")
    void expressRouteDirections() {
        assertThat(SubwayRoutePatternAssembler.selectExpressRoutes(SubwayPatternFixtures.ROUTES, "1009", "상행"))
                .extracting(OtpRailRoute::gtfsId).containsExactly("1:RR_ACC1_S-1-09-2D");
        assertThat(ends("1009", "상행")).containsExactly("김포공항→중앙보훈병원");
        assertThat(ends("1009", "하행")).containsExactly("중앙보훈병원→김포공항");

        assertThat(SubwayRoutePatternAssembler.selectExpressRoutes(SubwayPatternFixtures.ROUTES, "1001", "하행"))
                .extracting(OtpRailRoute::gtfsId)
                .containsExactlyInAnyOrder("1:RR_ACC1_S-1-01-4D", "1:RR_ACC1_S-1-01-6D");
        // 1호선 하행 = 서울에서 멀어지는 쪽(동인천·천안), 상행 = 서울 쪽
        assertThat(ends("1001", "하행")).contains("용산→동인천");
        assertThat(ends("1001", "상행")).contains("동인천→용산");
        assertThat(SubwayPatternFixtures.expressPatterns("1001", "하행").stream()
                .filter(p -> p.routeGtfsId().endsWith("01-6D")).map(p -> p.stops().get(0).name()).toList())
                .containsOnly("서울");
        assertThat(SubwayRoutePatternAssembler.selectExpressRoutes(SubwayPatternFixtures.ROUTES, "1002", "내선"))
                .isEmpty();
    }

    @Test
    @DisplayName("급행 패턴은 일반 패턴과 다른 캐시 키(line:dir:express)로 하루 둔다 — 일반 패턴 목록에 섞이지 않는다")
    void expressPatternsCachedSeparately() {
        SubwayRoutePatternPort port = mock(SubwayRoutePatternPort.class);
        OtpRailRoute local = new OtpRailRoute("1:RR_ACC1_S-1-09-1D", "서울9호선", "서울9호선(일반)<하행>");
        OtpRailRoute express = new OtpRailRoute("1:RR_ACC1_S-1-09-2D", "서울9호선", "서울9호선(급행)<하행>");
        given(port.subwayRoutes(any())).willReturn(List.of(local, express));
        given(port.patterns(eq(local.gtfsId()), any())).willReturn(List.of(new RailPattern(local.gtfsId(), 100,
                List.of(new RailPattern.Stop("가양", 0, 0, 0, 30), new RailPattern.Stop("증미", 0, 0, 90, 120)))));
        given(port.patterns(eq(express.gtfsId()), any())).willReturn(List.of(new RailPattern(express.gtfsId(), 50,
                List.of(new RailPattern.Stop("가양", 0, 0, 0, 30), new RailPattern.Stop("염창", 0, 0, 240, 270)))));
        SubwayLinePatternService service = new SubwayLinePatternService(port,
                java.time.Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), java.time.ZoneOffset.UTC));

        assertThat(service.expressLoaded("1009", "상행")).isFalse();
        assertThat(service.expressPatterns("1009", "상행")).singleElement()
                .satisfies(p -> assertThat(p.routeGtfsId()).isEqualTo(express.gtfsId()));
        assertThat(service.patterns("1009", "상행")).singleElement()
                .satisfies(p -> assertThat(p.routeGtfsId()).isEqualTo(local.gtfsId()));
        service.expressPatterns("1009", "상행");

        assertThat(service.expressLoaded("1009", "상행")).isTrue();
        assertThat(service.cachedExpressPatterns("1009", "상행")).hasSize(1);
        assertThat(service.cachedPatterns("1009", "상행")).hasSize(1);
        verify(port, times(1)).patterns(eq(express.gtfsId()), any());
        assertThat(SubwayLinePatternService.cacheKey("1009", "상행", true)).isEqualTo("1009:상행:express");
    }

    @Test
    @DisplayName("워밍업의 급행 노선 목록은 카탈로그 서비스 노선과 같다")
    void warmupExpressLinesMatchCatalog() {
        Set<String> catalogLines = SubwayExpressFixtures.catalog().services().stream()
                .map(SubwayExpressCatalog.ExpressService::lineId).collect(Collectors.toSet());

        assertThat(SubwayLinePatternWarmup.EXPRESS_LINE_IDS).isEqualTo(catalogLines);

        SubwayLinePatternService service = mock(SubwayLinePatternService.class);
        new SubwayLinePatternWarmup(service).warmUp();
        verify(service).expressPatterns("1009", "상행");
        verify(service).expressPatterns("1001", "하행");
        verify(service, times(catalogLines.size() * 2)).expressPatterns(any(), any());
    }
}
