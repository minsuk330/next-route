package watoo.grd.nextroute.application.subway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import watoo.grd.nextroute.application.subway.service.SubwayExpressCatalog.ExpressService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 급행 카탈로그 무결성. 역 이름·순서는 운영 OTP·역 마스터 fixture({@code otp/subway-patterns/}, 2026-10-07)와 대조한다 —
 * 카탈로그가 정답이지만 이름 오타·순서 뒤바뀜·없는 역은 여기서 잡는다.
 */
class SubwayExpressCatalogTest {

    static final List<String> SERVICE_IDS = List.of(
            "1001-gyeongin", "1001-gyeongbu-a", "1001-gyeongbu-b", "1001-gyeongwon", "1004-ansan", "1009-express",
            "1075-bundang", "1075-suin", "1063-seoul", "1063-gyeongui", "1063-jungang", "1067-express");

    static final SubwayExpressCatalog CATALOG =
            SubwayExpressCatalog.of(line -> SubwayPatternFixtures.STATIONS.getOrDefault(line, List.of()));

    /** fixture 패턴으로 각역 구간을 펼친다(운영의 {@code SubwayLineOrder::allStopPath} 와 같은 정적 계산). */
    static final SubwayExpressCatalog.AllStopPath FIXTURE_PATH =
            (lineId, direction, from, to) -> SubwayLineOrder.allStopPath(
                    SubwayPatternFixtures.patterns(lineId, direction), from, to);

    private static final DestinationNormalizer NORMALIZER = new DestinationNormalizer();

    private static SubwayExpressCatalog.Catalog raw() throws IOException {
        return SubwayExpressCatalog.parse(new ObjectMapper().readTree(
                new ClassPathResource(SubwayExpressCatalog.RESOURCE).getContentAsByteArray()));
    }

    private static String id(String lineId, String name) {
        return SubwayPatternFixtures.stationId(lineId, NORMALIZER.normalize(name));
    }

    @Test
    @DisplayName("서비스 12개가 파싱되고 전부 켜진다(역 이름을 하나도 빠짐없이 역 id 에 이었다)")
    void parsesTwelveServicesAllEnabled() throws IOException {
        assertThat(raw().services()).extracting(SubwayExpressCatalog.Definition::id)
                .containsExactlyInAnyOrderElementsOf(SERVICE_IDS);
        assertThat(CATALOG.services()).extracting(ExpressService::id).containsExactlyInAnyOrderElementsOf(SERVICE_IDS);
        assertThat(CATALOG.version()).isEqualTo("2026-10-21");
    }

    @Test
    @DisplayName("직통(공항철도)·ITX 는 카탈로그에 없다 — 분류기가 따로 판정한다")
    void noDirectOrItx() throws IOException {
        for (SubwayExpressCatalog.Definition def : raw().services()) {
            assertThat(def.lineId()).isNotEqualTo("1065");
            assertThat(def.label()).isEqualTo("급행");
            assertThat(def.id()).doesNotContainIgnoringCase("itx").doesNotContain("direct");
        }
    }

    @Test
    @DisplayName("서비스마다 출처·시행일·나열 방향이 있다")
    void metadata() throws IOException {
        for (SubwayExpressCatalog.Definition def : raw().services()) {
            assertThat(def.source()).as(def.id()).isNotEmpty();
            assertThat(def.effectiveFrom()).as(def.id()).isNotNull();
            assertThat(def.listedDirection()).as(def.id()).isIn("상행", "하행");
        }
    }

    @Test
    @DisplayName("모든 정차역·구간 끝·within 역 이름이 그 노선 역 마스터에서 정확히 하나로 해석된다")
    void everyNameResolvesToExactlyOneStation() throws IOException {
        for (SubwayExpressCatalog.Definition def : raw().services()) {
            List<String> names = new ArrayList<>(def.match().within());
            for (SubwayExpressCatalog.Element e : def.elements()) {
                if (e instanceof SubwayExpressCatalog.Stop stop) {
                    names.add(stop.name());
                } else if (e instanceof SubwayExpressCatalog.AllStops range) {
                    names.add(range.from());
                    names.add(range.to());
                }
            }
            for (String name : names) {
                long hits = SubwayPatternFixtures.STATIONS.get(def.lineId()).stream()
                        .filter(c -> NORMALIZER.normalize(name).equals(NORMALIZER.normalize(c.stationName())))
                        .count();
                assertThat(hits).as(def.id() + " " + name).isEqualTo(1);
            }
        }
    }

    static Stream<Arguments> serviceDirections() {
        return SERVICE_IDS.stream().flatMap(id -> Stream.of(Arguments.of(id, "상행"), Arguments.of(id, "하행")));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("serviceDirections")
    @DisplayName("정차 순서가 같은 노선·방향 완행 패턴의 부분수열이다(예외 정차 열차 기준)")
    void stopOrderIsSubsequenceOfAllStopPattern(String serviceId, String direction) {
        SubwayStopPattern pattern = CATALOG.pattern(serviceId, direction, exceptionTrain(serviceId), FIXTURE_PATH)
                .orElseThrow(() -> new AssertionError(serviceId + " " + direction + " 패턴을 못 만듦"));
        String lineId = pattern.lineId();
        List<StationsAheadCalculator.Pattern> allStop = SubwayPatternFixtures.patterns(lineId, direction);

        assertThat(pattern.stopIds()).hasSizeGreaterThan(5).doesNotHaveDuplicates();
        assertThat(allStop.stream().anyMatch(p -> isSubsequence(pattern.stopIds(),
                p.stops().stream().map(StationsAheadCalculator.Stop::key).toList())))
                .as(serviceId + " " + direction + " " + pattern.stopIds())
                .isTrue();
    }

    /** 예외 정차가 있으면 그 역에 서는 열차(가장 많은 역을 검사하려고). */
    private static String exceptionTrain(String serviceId) {
        return switch (serviceId) {
            case "1001-gyeongbu-a" -> "1903";
            case "1063-jungang" -> "5851";
            default -> null;
        };
    }

    private static boolean isSubsequence(List<String> sub, List<String> seq) {
        Iterator<String> it = seq.iterator();
        for (String want : sub) {
            boolean found = false;
            while (it.hasNext()) {
                if (want.equals(it.next())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    @Test
    @DisplayName("열차번호 규칙은 같은 노선 서비스끼리 겹치지 않고, 예외 정차 번호는 그 서비스 열차다")
    void trainNumbersDoNotOverlap() {
        Map<String, List<SubwayExpressCatalog.Service>> byLine = new HashMap<>();
        CATALOG.state().services().forEach(s -> byLine.computeIfAbsent(s.info().lineId(), k -> new ArrayList<>()).add(s));
        for (List<SubwayExpressCatalog.Service> services : byLine.values()) {
            for (int n = 0; n <= 9999; n++) {
                String no = String.valueOf(n);
                long owners = services.stream()
                        .filter(s -> s.match().trainNos().contains(no) || s.match().inRange(no))
                        .count();
                assertThat(owners).as(services.get(0).info().lineId() + " " + no).isLessThanOrEqualTo(1);
            }
        }
        Set<String> seen = new HashSet<>();
        for (SubwayExpressCatalog.Service service : CATALOG.state().services()) {
            for (SubwayExpressCatalog.ResolvedElement e : service.elements()) {
                if (e instanceof SubwayExpressCatalog.ResolvedStop stop) {
                    for (String no : stop.onlyTrains()) {
                        assertThat(CATALOG.resolve(service.info().lineId(), "하행", no, null, null))
                                .as(service.info().id() + " onlyTrains " + no)
                                .map(ExpressService::id).contains(service.info().id());
                        seen.add(service.info().lineId() + ":" + no);
                    }
                }
            }
        }
        assertThat(seen).contains("1001:1903", "1001:1947", "1063:5851");
    }

    @Test
    @DisplayName("1075-suin within 은 오이도→인천 완행 경로 그대로다(구간 판정 목록 오타 방지)")
    void suinWithinIsAllStopSpan() {
        SubwayExpressCatalog.Service suin = CATALOG.state().services().stream()
                .filter(s -> s.info().id().equals("1075-suin")).findFirst().orElseThrow();
        List<String> path = FIXTURE_PATH.path("1075", "하행", id("1075", "오이도"), id("1075", "인천"));

        assertThat(path).hasSize(14);
        assertThat(suin.within()).containsExactlyInAnyOrderElementsOf(path);
    }

    @Test
    @DisplayName("열차별 예외 — A급행 금천구청·의왕은 13편만, 번호를 모르면 빼고 준다")
    void perTrainStops() {
        String geumcheon = id("1001", "금천구청");
        String uiwang = id("1001", "의왕");

        assertThat(stops("1001-gyeongbu-a", "하행", "1903")).contains(geumcheon, uiwang);
        assertThat(stops("1001-gyeongbu-a", "상행", "K1906")).contains(geumcheon, uiwang);
        assertThat(stops("1001-gyeongbu-a", "하행", "1909")).doesNotContain(geumcheon, uiwang);
        assertThat(stops("1001-gyeongbu-a", "하행", null)).doesNotContain(geumcheon, uiwang);
        assertThat(stops("1063-jungang", "하행", "5851")).contains(id("1063", "팔당"));
        assertThat(stops("1063-jungang", "하행", "5853")).doesNotContain(id("1063", "팔당"));
    }

    @Test
    @DisplayName("방향 한정 — B급행 영등포는 상행만 선다")
    void directionalStop() {
        String yeongdeungpo = id("1001", "영등포");

        assertThat(stops("1001-gyeongbu-b", "상행", "1902")).contains(yeongdeungpo);
        assertThat(stops("1001-gyeongbu-b", "하행", "1945")).doesNotContain(yeongdeungpo);
    }

    @Test
    @DisplayName("반대 방향은 역순 — 9호선 나열은 상행(김포공항→중앙보훈병원), 하행은 중앙보훈병원부터")
    void reverseOrder() {
        List<String> up = stops("1009-express", "상행", "9634");
        List<String> down = stops("1009-express", "하행", "9625");

        assertThat(up.get(0)).isEqualTo(id("1009", "김포공항"));
        assertThat(up.get(up.size() - 1)).isEqualTo(id("1009", "중앙보훈병원"));
        List<String> reversed = new ArrayList<>(up);
        Collections.reverse(reversed);
        assertThat(down).containsExactlyElementsOf(reversed);
    }

    @Test
    @DisplayName("각역 구간을 펼친다 — 4호선 급행은 불암산~금정 각역 + 산본·상록수…, 수리산은 지나친다")
    void allStopsRangeExpands() {
        List<String> up = stops("1004-ansan", "상행", "4402");

        assertThat(up.get(0)).isEqualTo(id("1004", "오이도"));
        assertThat(up.get(up.size() - 1)).isEqualTo(id("1004", "불암산"));
        assertThat(up).contains(id("1004", "산본"), id("1004", "금정"), id("1004", "범계"), id("1004", "사당"))
                .doesNotContain(id("1004", "수리산"), id("1004", "대야미"), id("1004", "한대앞"));
    }

    @Test
    @DisplayName("각역 구간을 못 펼치거나 방향을 모르면 empty — 지어내지 않는다")
    void patternUnknown() {
        assertThat(CATALOG.pattern("1004-ansan", "상행", "4402", (l, d, a, b) -> List.of())).isEmpty();
        assertThat(CATALOG.pattern("1004-ansan", "상행", "4402", null)).isEmpty();
        assertThat(CATALOG.pattern("1009-express", "내선", "9625", FIXTURE_PATH)).isEmpty();
        assertThat(CATALOG.pattern("nope", "상행", "9625", FIXTURE_PATH)).isEmpty();
        // 각역 구간이 없는 서비스는 펼치개 없이도 된다
        assertThat(CATALOG.pattern("1009-express", "상행", "9634", null)).isPresent();
    }

    private static List<String> stops(String serviceId, String direction, String trainNo) {
        return CATALOG.pattern(serviceId, direction, trainNo, FIXTURE_PATH).orElseThrow().stopIds();
    }

    static List<RealtimeTrainSamples.Sample> expressSamples() {
        return RealtimeTrainSamples.ALL.stream().filter(s -> s.expectedService() != null).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expressSamples")
    @DisplayName("운영 실측 급행 표본(2026-10-21) → 서비스")
    void resolvesRealSamples(RealtimeTrainSamples.Sample sample) {
        Optional<ExpressService> service = CATALOG.resolve(sample.lineId(), sample.direction(), sample.trainNo(),
                sample.destination(), sample.currentStationId());

        assertThat(service).map(ExpressService::id).contains(sample.expectedService());
    }

    @Test
    @DisplayName("고르기 규칙 — 번호 목록 > 범위(제외 빼고) > 구간 > 종류만, 근거 없으면 empty")
    void resolveRules() {
        assertThat(resolved("1001", "하행", "1945", null, null)).contains("1001-gyeongbu-b");     // 목록이 범위(19xx)보다 먼저
        assertThat(resolved("1001", "상행", "K1904", null, null)).contains("1001-gyeongbu-b");
        assertThat(resolved("1001", "하행", "1951", null, null)).contains("1001-gyeongbu-a");
        assertThat(resolved("1001", "하행", "1615", "인천", null)).contains("1001-gyeongwon");  // 계획 리스크의 '1615 인천행 급행'
        assertThat(resolved("1001", "하행", "1801", null, null)).isEmpty();                     // 모르는 1호선 급행
        assertThat(resolved("1001", "하행", null, null, null)).isEmpty();
        // 수인분당은 구간으로 가른다 — 현재 역, 모르면 행선지
        assertThat(resolved("1075", "하행", "6303", null, id("1075", "정자"))).contains("1075-bundang");
        assertThat(resolved("1075", "상행", "6352", null, id("1075", "달월"))).contains("1075-suin");
        assertThat(resolved("1075", "하행", "6351", "인천", null)).contains("1075-suin");
        assertThat(resolved("1075", "하행", "6303", "고색", null)).contains("1075-bundang");
        assertThat(resolved("1075", "하행", "6303", null, null)).isEmpty();                     // 어디 있는지 모름
        assertThat(resolved("1009", "하행", "9625", null, null)).contains("1009-express");
        assertThat(resolved("1002", "내선", "2001", null, null)).isEmpty();                     // 급행 없는 노선
        assertThat(resolved(null, "하행", "9625", null, null)).isEmpty();
    }

    private static Optional<String> resolved(String lineId, String direction, String trainNo, String destination,
                                             String current) {
        return CATALOG.resolve(lineId, direction, trainNo, destination, current).map(ExpressService::id);
    }

    @Test
    @DisplayName("번호 권위 — 4호선 K44xx·1호선 급행 번호대만")
    void trainNoAuthority() {
        assertThat(CATALOG.isTrainNoAuthoritativeExpress("1004", "4402")).isTrue();
        assertThat(CATALOG.isTrainNoAuthoritativeExpress("1004", "K4405")).isTrue();
        assertThat(CATALOG.isTrainNoAuthoritativeExpress("1004", "4302")).isFalse();
        assertThat(CATALOG.isTrainNoAuthoritativeExpress("1001", "1932")).isTrue();
        assertThat(CATALOG.isTrainNoAuthoritativeExpress("1001", "1406")).isTrue();
        assertThat(CATALOG.isTrainNoAuthoritativeExpress("1001", "0660")).isFalse();
        assertThat(CATALOG.isTrainNoAuthoritativeExpress("1063", "2510")).isFalse();  // 실시간 표시를 따른다
        assertThat(CATALOG.isTrainNoAuthoritativeExpress("1009", "9625")).isFalse();
        assertThat(CATALOG.isTrainNoAuthoritativeExpress(null, "4402")).isFalse();
    }

    @Test
    @DisplayName("역 이름을 못 이은 서비스만 꺼진다(경고) — 다른 서비스는 그대로")
    void unresolvableServiceIsDisabled() {
        String json = """
                {"version":"t","services":[
                  {"id":"ok","lineId":"1009","listedDirection":"상행","match":{"kind":"EXPRESS"},
                   "stops":[{"name":"김포공항"},{"name":"마곡나루"}],"source":["x"],"effectiveFrom":"2026-01-01"},
                  {"id":"bad","lineId":"1009","listedDirection":"상행","match":{"trainNos":["K9999"]},
                   "stops":[{"name":"김포공항"},{"name":"없는역"}],"source":["x"],"effectiveFrom":"2026-01-01"}]}
                """;
        SubwayExpressCatalog catalog = SubwayExpressCatalog.of(json,
                line -> SubwayPatternFixtures.STATIONS.getOrDefault(line, List.of()));

        assertThat(catalog.services()).extracting(ExpressService::id).containsExactly("ok");
        assertThat(catalog.resolve("1009", "상행", "9999", null, null)).map(ExpressService::id).contains("ok");
        assertThat(catalog.pattern("bad", "상행", "9999", FIXTURE_PATH)).isEmpty();
    }

    @Test
    @DisplayName("파일을 못 읽으면 빈 카탈로그 — 급행 해석 없이 진행")
    void brokenFileMeansEmptyCatalog() {
        SubwayExpressCatalog catalog = SubwayExpressCatalog.of("{not json", line -> List.of());

        assertThat(catalog.services()).isEmpty();
        assertThat(catalog.version()).isNull();
        assertThat(catalog.resolve("1009", "하행", "9625", null, null)).isEmpty();
    }

    @Test
    @DisplayName("역 이름 → id 는 별칭을 거친다")
    void stationIdByName() {
        assertThat(CATALOG.stationId("1004", "당고개행")).contains(id("1004", "불암산"));
        assertThat(CATALOG.stationId("1001", "서울역")).contains(id("1001", "서울"));
        assertThat(CATALOG.stationId("1004", "진접")).isEmpty();
    }

    @Test
    @DisplayName("ExpressService 는 카탈로그 판을 들고 다닌다(바인딩 때 해석한 판을 유지하려고, S4)")
    void serviceCarriesVersion() {
        assertThat(CATALOG.service("1009-express")).map(ExpressService::catalogVersion).contains("2026-10-21");
        assertThat(CATALOG.service("1004-ansan")).map(ExpressService::trainNoAuthoritative).contains(true);
    }
}
