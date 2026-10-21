package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.service.StationsAheadCalculator.Pattern;
import watoo.grd.nextroute.application.subway.service.StationsAheadCalculator.Stop;
import watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver.PatternStop;
import watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver.ResolvedPattern;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SubwayStopPatternResolverTest {

    private static final DestinationNormalizer NORMALIZER = new DestinationNormalizer();

    private static String id(String lineId, String name) {
        return SubwayPatternFixtures.stationId(lineId, NORMALIZER.normalize(name));
    }

    private static List<String> ids(String lineId, String... names) {
        return Arrays.stream(names).map(n -> id(lineId, n)).toList();
    }

    @Nested
    @DisplayName("allStopPath — 두 역을 순서대로 지나는 패턴 중 사이 역 최다")
    class AllStopPath {

        @Test
        @DisplayName("급행 trip 패턴(A,X,Z)보다 완행(A,B,X,Y,Z)을 고른다")
        void prefersMostIntermediateStations() {
            List<Pattern> patterns = List.of(pattern(300, "A", "X", "Z"), pattern(100, "A", "B", "X", "Y", "Z"));

            assertThat(SubwayLineOrder.allStopPath(patterns, "A", "Z")).containsExactly("A", "B", "X", "Y", "Z");
            assertThat(SubwayLineOrder.allStopPath(patterns, "B", "Y")).containsExactly("B", "X", "Y");
        }

        @Test
        @DisplayName("순서가 반대거나 한쪽이 없으면 빈 목록, 같은 역이면 그 역")
        void unknown() {
            List<Pattern> patterns = List.of(pattern(100, "A", "B", "C"));

            assertThat(SubwayLineOrder.allStopPath(patterns, "C", "A")).isEmpty();
            assertThat(SubwayLineOrder.allStopPath(patterns, "A", "Q")).isEmpty();
            assertThat(SubwayLineOrder.allStopPath(List.of(), "A", "B")).isEmpty();
            assertThat(SubwayLineOrder.allStopPath(patterns, "B", "B")).containsExactly("B");
        }

        @Test
        @DisplayName("순환 패턴은 승차역이 다시 나오기 전까지만 본다")
        void loop() {
            List<Pattern> patterns = List.of(pattern(10, "A", "B", "C", "A", "B", "C"));

            assertThat(SubwayLineOrder.allStopPath(patterns, "C", "B")).containsExactly("C", "A", "B");
        }

        @Test
        @DisplayName("같은 길이면 trip 많은 패턴")
        void tieBreaksOnTripCount() {
            List<Pattern> patterns = List.of(pattern(1, "A", "Q", "C"), pattern(50, "A", "B", "C"));

            assertThat(SubwayLineOrder.allStopPath(patterns, "A", "C")).containsExactly("A", "B", "C");
        }

        @Test
        @DisplayName("운영 fixture — 9호선 하행 고속터미널→가양은 완행 17역")
        void fixtureLine9() {
            List<String> path = SubwayLineOrder.allStopPath(SubwayPatternFixtures.patterns("1009", "하행"),
                    id("1009", "고속터미널"), id("1009", "가양"));

            assertThat(path).containsExactlyElementsOf(ids("1009", "고속터미널", "신반포", "구반포", "동작", "흑석", "노들",
                    "노량진", "샛강", "여의도", "국회의사당", "당산", "선유도", "신목동", "염창", "등촌", "증미", "가양"));
        }

        @Test
        @DisplayName("패턴 캐시가 비면 빈 목록(OTP 를 부르지 않고 지연 적재만)")
        void cacheMiss() {
            SubwayLinePatternService patternService = mock(SubwayLinePatternService.class);
            given(patternService.cachedPatterns("1009", "하행")).willReturn(List.of());
            SubwayLineOrder order = new SubwayLineOrder(patternService, mock(SubwayLineStationIndex.class),
                    mock(SubwayStationsAheadService.class));

            assertThat(order.allStopPath("1009", "하행", "a", "b")).isEmpty();
            assertThat(order.allStopPath("1009", "하행", null, "b")).isEmpty();
        }
    }

    @Nested
    @DisplayName("resolve — 서비스·정차역·종착 범위")
    class Resolve {

        private SubwayStopPatternResolver resolver;

        @BeforeEach
        void setUp() {
            SubwayLineOrder lineOrder = mock(SubwayLineOrder.class);
            given(lineOrder.allStopPath(any(), any(), any(), any())).willAnswer(inv -> SubwayLineOrder.allStopPath(
                    SubwayPatternFixtures.patterns(inv.getArgument(0), inv.getArgument(1)),
                    inv.getArgument(2), inv.getArgument(3)));
            resolver = new SubwayStopPatternResolver(SubwayExpressCatalogTest.CATALOG, lineOrder);
        }

        @Test
        @DisplayName("9호선 급행 하행(김포공항행) — 중앙보훈병원부터 김포공항까지, 언주는 통과")
        void line9() {
            ResolvedPattern p = resolver.resolve("1009", "하행", "9625", "김포공항", id("1009", "마곡나루")).orElseThrow();

            assertThat(p.serviceId()).isEqualTo("1009-express");
            assertThat(p.catalogVersion()).isEqualTo("2026-10-21");
            assertThat(p.stopIds().get(0)).isEqualTo(id("1009", "중앙보훈병원"));
            assertThat(p.stopIds().get(p.stopIds().size() - 1)).isEqualTo(id("1009", "김포공항"));
            assertThat(p.terminalStationId()).isEqualTo(id("1009", "김포공항"));
            assertThat(p.stopsAt(id("1009", "신논현"))).isTrue();
            assertThat(p.stopsAt(id("1009", "언주"))).isFalse();
        }

        @Test
        @DisplayName("종착역까지 자른다 — 경인 급행 구로행은 용산~구로 7역")
        void cutsAtTerminal() {
            ResolvedPattern p = resolver.resolve("1001", "하행", "1101", "구로", null).orElseThrow();

            assertThat(p.stopIds()).containsExactlyElementsOf(
                    ids("1001", "용산", "노량진", "대방", "신길", "영등포", "신도림", "구로"));
            assertThat(p.stopsAt(id("1001", "개봉"))).isFalse();
        }

        @Test
        @DisplayName("행선지 대신 종착 역 id(위치 기반 출발 기록) — 경원 급행 부평행은 광운대 이남 각역을 펼쳐 부평에서 자른다")
        void terminalIdAndAllStopsRange() {
            ResolvedPattern p = resolver.resolve("1001", "하행", "1601", id("1001", "부평"), null).orElseThrow();

            assertThat(p.serviceId()).isEqualTo("1001-gyeongwon");
            assertThat(p.stopIds().get(p.stopIds().size() - 1)).isEqualTo(id("1001", "부평"));
            assertThat(p.stopIds()).contains(id("1001", "덕계"), id("1001", "석계"), id("1001", "구일"))
                    .doesNotContain(id("1001", "녹천"), id("1001", "보산"), id("1001", "동인천"));
        }

        @Test
        @DisplayName("행선지가 노선 역 마스터에 없으면 자르지 않는다 — 4호선 진접행")
        void unknownTerminalKeepsFullPattern() {
            ResolvedPattern p = resolver.resolve("1004", "상행", "4402", "진접", null).orElseThrow();

            assertThat(p.terminalStationId()).isNull();
            assertThat(p.stopIds().get(p.stopIds().size() - 1)).isEqualTo(id("1004", "불암산"));
        }

        @Test
        @DisplayName("모르면 empty — 서비스 없음, 방향 모름, 행선지가 서지 않는 역, 각역 구간 못 펼침")
        void unknownIsEmpty() {
            assertThat(resolver.resolve("1001", "하행", "1801", "동인천", null)).isEmpty();
            assertThat(resolver.resolve("1009", null, "9625", "김포공항", null)).isEmpty();
            assertThat(resolver.resolve("1009", "하행", "9625", "개화", null)).isEmpty();   // 급행은 개화에 안 선다
            assertThat(resolver.resolve("1002", "내선", "2001", "성수", null)).isEmpty();

            SubwayLineOrder empty = mock(SubwayLineOrder.class);
            given(empty.allStopPath(any(), any(), any(), any())).willReturn(List.of());
            SubwayStopPatternResolver cold = new SubwayStopPatternResolver(SubwayExpressCatalogTest.CATALOG, empty);
            assertThat(cold.resolve("1004", "상행", "4402", "불암산", null)).isEmpty();
            assertThat(cold.resolve("1009", "하행", "9625", "김포공항", null)).isPresent(); // 구간 없는 서비스는 된다
        }

        @Test
        @DisplayName("열차별 예외는 번호로 — A급행 1903 은 금천구청에 서고 1909 는 안 선다")
        void perTrainException() {
            Optional<ResolvedPattern> stopsThere = resolver.resolve("1001", "하행", "K1903", "천안", null);
            Optional<ResolvedPattern> passes = resolver.resolve("1001", "하행", "1909", "천안", null);

            assertThat(stopsThere.orElseThrow().stopsAt(id("1001", "금천구청"))).isTrue();
            assertThat(passes.orElseThrow().stopsAt(id("1001", "금천구청"))).isFalse();
            assertThat(passes.get().stopIds().get(passes.get().stopIds().size() - 1)).isEqualTo(id("1001", "천안"));
        }
    }

    @Nested
    @DisplayName("trim — 정차역만, 앞에서 지나친 역(passingBefore)")
    class Trim {

        @Test
        @DisplayName("9호선 급행 하행 고속터미널→가양: 정차 7역, 각 역 앞의 통과역")
        void line9Trim() {
            ResolvedPattern pattern = new ResolvedPattern(SubwayExpressCatalogTest.CATALOG.service("1009-express")
                    .orElseThrow(), "하행", SubwayExpressCatalogTest.CATALOG.pattern("1009-express", "하행", "9625",
                    SubwayExpressCatalogTest.FIXTURE_PATH).orElseThrow().stopIds(), null);
            List<String> all = SubwayLineOrder.allStopPath(SubwayPatternFixtures.patterns("1009", "하행"),
                    id("1009", "고속터미널"), id("1009", "가양"));

            List<PatternStop> trimmed = SubwayStopPatternResolver.trim(all, pattern);

            assertThat(trimmed).containsExactly(
                    new PatternStop(id("1009", "고속터미널"), List.of()),
                    new PatternStop(id("1009", "동작"), ids("1009", "신반포", "구반포")),
                    new PatternStop(id("1009", "노량진"), ids("1009", "흑석", "노들")),
                    new PatternStop(id("1009", "여의도"), ids("1009", "샛강")),
                    new PatternStop(id("1009", "당산"), ids("1009", "국회의사당")),
                    new PatternStop(id("1009", "염창"), ids("1009", "선유도", "신목동")),
                    new PatternStop(id("1009", "가양"), ids("1009", "등촌", "증미")));
        }

        @Test
        @DisplayName("하차역에 안 서면 꼬리 통과역은 버리고 stopsAt 이 false")
        void alightNotAStop() {
            ResolvedPattern pattern = new ResolvedPattern(
                    SubwayExpressCatalogTest.CATALOG.service("1009-express").orElseThrow(), "하행",
                    List.of("A", "C"), null);

            List<PatternStop> trimmed = SubwayStopPatternResolver.trim(List.of("A", "B", "C", "D"), pattern);

            assertThat(trimmed).containsExactly(new PatternStop("A", List.of()), new PatternStop("C", List.of("B")));
            assertThat(pattern.stopsAt("D")).isFalse();
        }

        @Test
        @DisplayName("첫 역이 정차역이 아니면 그 역은 첫 정차역의 passingBefore 로 간다")
        void leadingPassing() {
            ResolvedPattern pattern = new ResolvedPattern(
                    SubwayExpressCatalogTest.CATALOG.service("1009-express").orElseThrow(), "하행", List.of("C"), null);

            assertThat(SubwayStopPatternResolver.trim(List.of("A", "B", "C"), pattern))
                    .containsExactly(new PatternStop("C", List.of("A", "B")));
            assertThat(SubwayStopPatternResolver.trim(List.of(), pattern)).isEmpty();
        }
    }

    @Nested
    @DisplayName("allStopPathEitherDirection — 방향 라벨 없이(subway-express S2)")
    class EitherDirection {

        private SubwayStopPatternResolver resolverWith(List<Pattern> up, List<Pattern> down) {
            SubwayLineOrder lineOrder = mock(SubwayLineOrder.class);
            given(lineOrder.allStopPath(any(), any(), any(), any())).willAnswer(inv -> SubwayLineOrder.allStopPath(
                    "상행".equals(inv.getArgument(1)) ? up : down, inv.getArgument(2), inv.getArgument(3)));
            return new SubwayStopPatternResolver(SubwayExpressCatalogTest.CATALOG, lineOrder);
        }

        @Test
        @DisplayName("직선 노선은 두 역을 이 순서로 지나는 방향이 하나 — 그 방향의 완행 경로")
        void linear() {
            SubwayStopPatternResolver resolver = resolverWith(
                    List.of(pattern(10, "C", "B", "A")), List.of(pattern(10, "A", "B", "C")));

            assertThat(resolver.allStopPathEitherDirection("1009", null, "A", "C")).containsExactly("A", "B", "C");
            assertThat(resolver.allStopPathEitherDirection("1009", null, "C", "A")).containsExactly("C", "B", "A");
            assertThat(resolver.allStopPathEitherDirection(null, null, "A", "C")).isEmpty();
        }

        @Test
        @DisplayName("두 방향 다 지나면(순환) 선호 방향, 선호가 없으면 empty — 고르지 않는다")
        void loopNeedsPreference() {
            SubwayStopPatternResolver resolver = resolverWith(
                    List.of(pattern(10, "A", "B", "C", "A")), List.of(pattern(10, "A", "D", "C", "A")));

            assertThat(resolver.allStopPathEitherDirection("1009", null, "A", "C")).isEmpty();
            assertThat(resolver.allStopPathEitherDirection("1009", "하행", "A", "C")).containsExactly("A", "D", "C");
            assertThat(resolver.allStopPathEitherDirection("1009", "상행", "A", "C")).containsExactly("A", "B", "C");
        }
    }

    private static Pattern pattern(int trips, String... keys) {
        return new Pattern("R", "R", false, trips,
                Arrays.stream(keys).map(k -> new Stop(k, k, k, null, null)).toList());
    }
}
