package watoo.grd.nextroute.application.subway.service;

import watoo.grd.nextroute.application.route.routing.RailServiceClassifier;
import watoo.grd.nextroute.application.subway.dto.RailPattern;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 다른 패키지 시험이 급행 판정을 운영 fixture({@code otp/subway-patterns/}, 2026-10-07)로 돌리게 하는 공개 도구
 * (subway-express S2). 패턴 캐시가 데워진 운영 상태와 같다 — {@link SubwayLineOrder#allStopPath} 정적 계산을 쓴다.
 */
public final class SubwayExpressFixtures {

    private static final DestinationNormalizer NORMALIZER = new DestinationNormalizer();

    private SubwayExpressFixtures() {
    }

    public static SubwayExpressCatalog catalog() {
        return SubwayExpressCatalogTest.CATALOG;
    }

    /** 패턴 캐시가 데워진 해석기. */
    public static SubwayStopPatternResolver resolver() {
        return new SubwayStopPatternResolver(catalog(), warmLineOrder());
    }

    /** 패턴 캐시가 빈(부팅 직후·worker 첫 재탐색) 해석기. */
    public static SubwayStopPatternResolver coldResolver() {
        SubwayLineOrder lineOrder = mock(SubwayLineOrder.class);
        given(lineOrder.allStopPath(any(), any(), any(), any())).willReturn(List.of());
        return new SubwayStopPatternResolver(catalog(), lineOrder);
    }

    public static SubwayLineOrder warmLineOrder() {
        SubwayLineOrder lineOrder = mock(SubwayLineOrder.class);
        given(lineOrder.allStopPath(any(), any(), any(), any())).willAnswer(inv -> {
            String board = inv.getArgument(2);
            String alight = inv.getArgument(3);
            if (board == null || alight == null) {
                return List.of();
            }
            return SubwayLineOrder.allStopPath(
                    SubwayPatternFixtures.patterns(inv.getArgument(0), inv.getArgument(1)), board, alight);
        });
        return lineOrder;
    }

    public static SubwayLineStationIndex stationIndex() {
        SubwayLineStationIndex index = mock(SubwayLineStationIndex.class);
        given(index.candidates(anyString())).willAnswer(inv ->
                SubwayPatternFixtures.STATIONS.getOrDefault(inv.<String>getArgument(0), List.of()));
        return index;
    }

    /** 운영 fixture 위의 분류기(패턴 캐시 데워짐). */
    public static RailServiceClassifier railServiceClassifier() {
        return new RailServiceClassifier(catalog(), resolver(), stationIndex(), mock(SubwayLinePatternService.class));
    }

    /** 패턴 캐시가 빈 분류기(이름 기준 폴백만 남는다). */
    public static RailServiceClassifier coldRailServiceClassifier() {
        return new RailServiceClassifier(catalog(), coldResolver(), stationIndex(),
                mock(SubwayLinePatternService.class));
    }

    /** fixture 노선의 OTP 패턴들(정차 이름·좌표). */
    public static List<RailPattern> routePatterns(String routeGtfsId) {
        List<RailPattern> patterns = SubwayPatternFixtures.PATTERNS.get(routeGtfsId);
        if (patterns == null) {
            throw new IllegalArgumentException(routeGtfsId);
        }
        return patterns;
    }

    /** 노선의 역 마스터 id(정규화 이름으로 찾는다). */
    public static String stationId(String lineId, String name) {
        return SubwayPatternFixtures.stationId(lineId, NORMALIZER.normalize(name));
    }
}
