package watoo.grd.nextroute.application.subway.service;

import watoo.grd.nextroute.application.subway.config.SubwayExpressTrackingProperties;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 다른 패키지 시험이 운영 fixture(역 마스터·OTP 패턴, 2026-10-07)와 번들 급행 카탈로그로 실제 판정을 돌리게 한다
 * (subway-express PR-S3). {@link SubwayPatternFixtures}·{@link SubwayExpressCatalogTest#CATALOG} 는 패키지 전용이라 여기서 연다.
 */
public final class SubwayExpressFixtures {

    private static final DestinationNormalizer NORMALIZER = new DestinationNormalizer();

    private SubwayExpressFixtures() {
    }

    public static SubwayExpressCatalog catalog() {
        return SubwayExpressCatalogTest.CATALOG;
    }

    public static SubwayTrainKindClassifier classifier() {
        return new SubwayTrainKindClassifier(catalog());
    }

    /** {@code allStopPath} 를 fixture 패턴으로 계산하는 노선 순서. */
    public static SubwayLineOrder lineOrder() {
        SubwayLineOrder lineOrder = mock(SubwayLineOrder.class);
        given(lineOrder.allStopPath(any(), any(), any(), any())).willAnswer(inv -> SubwayLineOrder.allStopPath(
                SubwayPatternFixtures.patterns(inv.getArgument(0), inv.getArgument(1)),
                inv.getArgument(2), inv.getArgument(3)));
        return lineOrder;
    }

    public static SubwayStopPatternResolver resolver() {
        return new SubwayStopPatternResolver(catalog(), lineOrder());
    }

    public static SubwayExpressTrackingProperties tracking(boolean enabled) {
        SubwayExpressTrackingProperties properties = new SubwayExpressTrackingProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    /** 실제 카탈로그·fixture 패턴으로 판정하는 심판. 플래그는 {@code properties} 를 바꿔 켜고 끈다. */
    public static SubwayTrainKindJudge judge(SubwayExpressTrackingProperties properties) {
        return new SubwayTrainKindJudge(classifier(), resolver(), properties);
    }

    /** fixture 역 마스터의 역 id(이름은 정규화 전 표기도 받는다). */
    public static String stationId(String lineId, String name) {
        return SubwayPatternFixtures.stationId(lineId, NORMALIZER.normalize(name));
    }

    /** fixture 역 마스터의 그 노선 역 전부(역 id·이름) — 링 역 해석기의 역 마스터 대역(subway-express S4). */
    public static List<watoo.grd.nextroute.domain.subway.entity.SubwayStation> stations(String lineId) {
        return SubwayPatternFixtures.STATIONS.getOrDefault(lineId, List.of()).stream()
                .map(c -> watoo.grd.nextroute.domain.subway.entity.SubwayStation.builder()
                        .stationId(c.stationId()).stationName(c.stationName()).lineId(lineId)
                        .latitude(c.lat()).longitude(c.lon()).build())
                .toList();
    }

    /** fixture 완행 경로(양 끝 포함). */
    public static List<String> allStopPath(String lineId, String direction, String board, String alight) {
        return SubwayLineOrder.allStopPath(SubwayPatternFixtures.patterns(lineId, direction), board, alight);
    }

    /**
     * 운영 fixture 패턴(일반 + 노선형 급행)으로 도는 <b>진짜</b> {@link SubwayLineOrder}(subway-express S4). 패턴 서비스·역 연결 캐시만
     * 대역이다 — 구간 시간·급행 정확 일치 계산은 운영 코드 그대로다.
     */
    public static SubwayLineOrder fixtureLineOrder() {
        SubwayLinePatternService patternService = mock(SubwayLinePatternService.class);
        List<watoo.grd.nextroute.application.subway.dto.DirectedRailPattern> marker =
                List.of(mock(watoo.grd.nextroute.application.subway.dto.DirectedRailPattern.class));
        org.mockito.Mockito.lenient().when(patternService.cachedPatterns(any(), any())).thenReturn(marker);
        org.mockito.Mockito.lenient().when(patternService.cachedExpressPatterns(any(), any())).thenReturn(marker);
        org.mockito.Mockito.lenient().when(patternService.expressLoaded(any(), any())).thenReturn(true);
        SubwayStationsAheadService ahead = mock(SubwayStationsAheadService.class);
        org.mockito.Mockito.lenient().when(ahead.resolvedPatterns(any(), any(), any(), any())).thenAnswer(inv -> {
            String lineId = inv.getArgument(0);
            String key = inv.getArgument(1);
            return key.endsWith(":express")
                    ? SubwayPatternFixtures.expressPatterns(lineId, key.substring(0, key.length() - ":express".length()))
                    : SubwayPatternFixtures.patterns(lineId, key);
        });
        SubwayLineStationIndex index = mock(SubwayLineStationIndex.class);
        org.mockito.Mockito.lenient().when(index.candidates(any())).thenReturn(List.of());
        return new SubwayLineOrder(patternService, index, ahead);
    }

    public static List<String> stationIds(String lineId, String... names) {
        return java.util.Arrays.stream(names).map(name -> stationId(lineId, name)).toList();
    }
}
