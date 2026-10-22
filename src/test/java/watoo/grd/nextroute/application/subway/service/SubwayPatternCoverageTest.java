package watoo.grd.nextroute.application.subway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import watoo.grd.nextroute.application.subway.dto.DirectedRailPattern;
import watoo.grd.nextroute.application.subway.dto.OtpRailRoute;
import watoo.grd.nextroute.application.subway.dto.RailPattern;
import watoo.grd.nextroute.infrastructure.adapter.out.api.otp.OtpSubwayRoutePatternAdapter;

import java.io.IOException;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>운영 OTP 패턴 × 운영 역 마스터 × 운영 실시간 행선지</b>로 노선 선택·방향 보정·역 연결을 검증한다(#283).
 *
 * <p>fixture 는 2026-10-07 VPS 에서 그대로 받았다({@code otp/subway-patterns/}). 고른 노선 몇 개로
 * 통과하는 시험은 소용이 없다 — 방향 라벨이 실시간과 반대인 노선(경의중앙선 서울역)과 방향이 없는
 * 노선(6호선)은 전수로 봐야 드러났다. feed 가 갱신돼 표기가 바뀌면 이 시험이 먼저 깨지는 게 목적이다.
 */
@DisplayName("지하철 패턴 전 노선 커버리지")
class SubwayPatternCoverageTest {

    /** OTP feed 에 쓸 만한 노선이 없는 line_id. 이 노선은 남은 역 목록을 못 준다. */
    static final Set<String> UNSUPPORTED_LINES = Set.of();

    /**
     * 그 노선·방향 전체 건수 중 이 비율 미만인 실시간 행선지는 잡음(회송·임시)으로 보고 대조하지 않는다.
     * 예: 우이신설 상행 행선지 북한산우이 22건(4%) — 상행의 종착은 신설동이다.
     */
    private static final double MIN_DESTINATION_SHARE = 0.05;

    /** OTP 패턴에 없는 실시간 행선지. 이 열차를 탄 사용자는 그 구간 역을 못 받는다. */
    static final Set<String> KNOWN_DESTINATION_GAPS = Set.of(
            "1067 상행 → 용산"); // 경춘선 용산 직결(ITX 등). OTP 경춘선은 상봉·청량리까지

    /** 역 마스터(subway_station)에 아직 없는 역. 정적 일배치가 꺼져 있어 연장 구간이 빠졌다. */
    static final Map<String, Set<String>> KNOWN_UNMATCHED_STOPS = Map.of(
            "1004", Set.of("별내별가람", "오남", "진접"));

    /** 이름은 다르지만 같은 역(개명·표기 차이). 좌표 대체 연결은 이 목록 안에서만 허용한다. */
    static final Set<String> KNOWN_RENAMES = Set.of(
            "1001 초성리 → 청산", // 좌표 25m. 초성리역은 연천군 청산면에 있다 — 역 마스터가 옛 표기
            "1001 평택지제 → 지제",
            "1004 당고개 → 불암산",
            "1006 응암 → 응암순환(상선)",
            "1063 화전 → 한국항공대",
            "1081 세종대왕릉 → 세종왕릉",
            "1092 4·19민주묘지 → 4.19 민주묘지");

    /** 실시간 행선지 표기 → 역 이름. 지선·순환 표기가 역 이름과 다르다. */
    static final Map<String, String> DESTINATION_ALIASES = Map.of(
            "응암순환(상선)", "응암",
            "성수지선", "성수",
            "신도림지선", "신도림");

    private static final ObjectMapper OM = new ObjectMapper();
    private static final DestinationNormalizer NORMALIZER = new DestinationNormalizer();

    private static List<OtpRailRoute> routes;
    private static Map<String, List<RailPattern>> patternsByRoute;
    private static Map<String, List<SubwayPatternStationMatcher.Candidate>> stationsByLine;
    private static List<JsonNode> destinations;
    /** {@code lineId|역|방향} → 그 방향 다음 역(정규화 이름). 도착 API {@code trainLineNm} 의 "Y방면"이 출처다. */
    private static Map<String, Set<String>> nextStations;

    @BeforeAll
    static void load() throws IOException {
        JsonNode root = read("otp/subway-patterns/routes-with-patterns.json");
        routes = new ArrayList<>();
        patternsByRoute = new HashMap<>();
        for (JsonNode route : root.path("routes")) {
            String id = route.path("gtfsId").asText();
            routes.add(new OtpRailRoute(id, route.path("shortName").asText(), route.path("longName").asText()));
            patternsByRoute.put(id, OtpSubwayRoutePatternAdapter.parsePatterns(id, route));
        }
        stationsByLine = new TreeMap<>();
        for (JsonNode s : read("otp/subway-patterns/subway-stations.json").path("stations")) {
            stationsByLine.computeIfAbsent(s.path("lineId").asText(), k -> new ArrayList<>())
                    .add(new SubwayPatternStationMatcher.Candidate(s.path("stationId").asText(),
                            s.path("stationName").asText(), s.path("latitude").asDouble(), s.path("longitude").asDouble()));
        }
        destinations = new ArrayList<>();
        read("otp/subway-patterns/realtime-destinations.json").path("destinations").forEach(destinations::add);
        nextStations = new HashMap<>();
        for (JsonNode n : read("otp/subway-patterns/realtime-next-stations.json").path("next")) {
            nextStations.computeIfAbsent(nextKey(n.path("lineId").asText(),
                            NORMALIZER.normalize(n.path("stationName").asText()), n.path("direction").asText()),
                    k -> new HashSet<>()).add(NORMALIZER.normalize(n.path("nextStationName").asText()));
        }
    }

    /**
     * 실시간이 "이 역 다음은 저 역"이라고 말한 것과 패턴 진행이 맞는다 — 역 하나하나의 방향을 본다(2026-10-22 감사).
     *
     * <p>행선지 대조({@link #realtimeDestinationsLieAheadInTheirDirection})는 노선·방향 단위라 지선 4역(성수지선)처럼 행선지가
     * 본선과 겹치는 구간을 놓칠 수 있다. 여기서는 도착 API 한 행의 {@code updnLine} 과 "Y방면"(그 방향 바로 다음 역)으로 모든 연속
     * 정차 쌍을 대조한다. 반대 방향 쪽에서만 다음 역으로 나오면 위반이다.
     */
    @Test
    @DisplayName("패턴의 연속 두 역은 실시간이 그 방향 '다음 역'이라 부른 쌍이다 — 역 단위 방향 대조")
    void everyPatternStepMatchesRealtimeNextStation() {
        List<String> violations = new ArrayList<>();
        Map<String, Integer> checked = new TreeMap<>();
        for (String lineId : stationsByLine.keySet()) {
            for (String direction : directionsOf(lineId)) {
                String opposite = SubwayTrainIdentity.opposite(direction);
                for (DirectedRailPattern pattern : directed(lineId, direction)) {
                    List<RailPattern.Stop> stops = pattern.stops();
                    for (int i = 0; i + 1 < stops.size(); i++) {
                        String a = stationName(lineId, stops.get(i));
                        String b = stationName(lineId, stops.get(i + 1));
                        boolean same = nextStations.getOrDefault(nextKey(lineId, a, direction), Set.of()).contains(b);
                        boolean reverse = nextStations.getOrDefault(nextKey(lineId, a, opposite), Set.of()).contains(b);
                        if (same || reverse) {
                            checked.merge(pattern.routeGtfsId(), 1, Integer::sum);
                        }
                        if (reverse && !same) {
                            violations.add(lineId + " " + direction + " " + pattern.routeGtfsId() + " " + a + "→" + b);
                        }
                    }
                }
            }
        }
        assertThat(violations).as("실시간으로는 반대 방향인 패턴 구간").isEmpty();
        // 반전표 노선과 신정지선이 실제로 대조됐는지 — 픽스처가 비면 위 단언이 거저 통과한다
        assertThat(checked.keySet()).contains(
                "1:RR_ACC1_S-1-02-2I", "1:RR_ACC1_S-1-02-2O", "1:RR_ACC1_S-1-02-3I", "1:RR_ACC1_S-1-02-3O",
                "1:RR_ACC1_S-1-09-1U", "1:RR_ACC1_S-1-09-1D", "1:RR_ACC1_S-1-AP-1U", "1:RR_ACC1_S-1-AP-1D",
                "1:RR_ACC1_S-1-KJ-2U", "1:RR_ACC1_S-1-KJ-2D", "1:RR_ACC1_S-1-06-00");
    }

    /**
     * 검색 {@code wayCode} 쪽: 방향 라벨이 있는 모든 노선(급행 포함)의 라벨({@link SubwayDirectionNormalizer#realtimeLabelFrom}, 반전표
     * 적용)이 실시간 다음 역과 맞는다. 반전표에 빠진 노선도, 필요 없는데 들어간 노선도 여기서 깨진다.
     */
    @Test
    @DisplayName("노선 라벨(반전표 적용)이 실시간 다음 역 방향과 같다 — 반전표 누락·과잉을 잡는다")
    void routeLabelsMatchRealtimeNextStation() {
        List<String> violations = new ArrayList<>();
        Map<String, Integer> agree = new TreeMap<>();
        for (OtpRailRoute route : routes) {
            String lineId = SubwayRoutePatternAssembler.lineIdOf(route.shortName()).orElse(null);
            String label = SubwayDirectionNormalizer.realtimeLabelFrom(route.gtfsId(), route.longName());
            if (lineId == null || label == null || !stationsByLine.containsKey(lineId)) {
                continue;
            }
            String opposite = SubwayTrainIdentity.opposite(label);
            for (RailPattern pattern : patternsByRoute.get(route.gtfsId())) {
                List<RailPattern.Stop> stops = pattern.stops();
                for (int i = 0; i + 1 < stops.size(); i++) {
                    String a = stationName(lineId, stops.get(i));
                    String b = stationName(lineId, stops.get(i + 1));
                    boolean same = nextStations.getOrDefault(nextKey(lineId, a, label), Set.of()).contains(b);
                    boolean reverse = nextStations.getOrDefault(nextKey(lineId, a, opposite), Set.of()).contains(b);
                    if (same) {
                        agree.merge(route.gtfsId(), 1, Integer::sum);
                    } else if (reverse) {
                        violations.add(route.gtfsId() + " " + route.longName() + " → " + label + " " + a + "→" + b);
                    }
                }
            }
        }
        assertThat(violations).as("라벨이 실시간과 반대인 노선 구간 — REVERSED_LABEL_ROUTES 를 볼 것").isEmpty();
        assertThat(agree.keySet()).containsAll(SubwayDirectionNormalizer.REVERSED_LABEL_ROUTES.stream()
                .filter(id -> !id.contains("09-2")) // 9호선 급행은 연속 정차가 인접역이 아니어서 대조 쌍이 적다
                .map(id -> "1:" + id).toList());
    }

    private static String nextKey(String lineId, String station, String direction) {
        return lineId + "|" + station + "|" + direction;
    }

    @Test
    @DisplayName("모든 노선·방향에 패턴이 있다 — 알려진 예외만 비어 있다")
    void everyLineDirectionHasPatterns() {
        List<String> missing = new ArrayList<>();
        for (String lineId : stationsByLine.keySet()) {
            for (String direction : directionsOf(lineId)) {
                if (directed(lineId, direction).isEmpty()) {
                    missing.add(lineId + " " + direction);
                }
            }
        }
        assertThat(missing.stream().map(s -> s.substring(0, 4)).distinct().toList())
                .as("패턴 없는 노선·방향: %s", missing)
                .containsExactlyInAnyOrderElementsOf(UNSUPPORTED_LINES);
    }

    @Test
    @DisplayName("실시간 행선지가 그 방향 패턴의 진행 쪽에 있다 — 방향이 뒤집힌 노선을 잡는다")
    void realtimeDestinationsLieAheadInTheirDirection() {
        List<String> violations = new ArrayList<>();
        for (JsonNode d : destinations) {
            String lineId = d.path("lineId").asText();
            String direction = d.path("direction").asText();
            if (UNSUPPORTED_LINES.contains(lineId) || !stationsByLine.containsKey(lineId)
                    || d.path("n").asInt() < MIN_DESTINATION_SHARE * total(lineId, direction)) {
                continue;
            }
            String raw = d.path("destinationName").asText();
            String target = NORMALIZER.normalize(DESTINATION_ALIASES.getOrDefault(raw, raw));
            boolean ahead = directed(lineId, direction).stream().anyMatch(p -> {
                List<RailPattern.Stop> stops = p.stops();
                for (int i = 1; i < stops.size(); i++) {
                    if (target.equals(stationName(lineId, stops.get(i)))
                            || target.equals(NORMALIZER.normalize(stops.get(i).name()))) return true;
                }
                return false;
            });
            if (!ahead) {
                violations.add(lineId + " " + direction + " → " + raw);
            }
        }
        assertThat(violations).as("진행 쪽에서 못 찾은 실시간 행선지")
                .containsExactlyInAnyOrderElementsOf(KNOWN_DESTINATION_GAPS);
    }

    @Test
    @DisplayName("지원 노선의 모든 정차가 역에 이어진다 — 알려진 예외만 끊긴다")
    void everyStopMatchesAStation() {
        Map<String, Set<String>> unmatched = new TreeMap<>();
        for (String lineId : stationsByLine.keySet()) {
            if (UNSUPPORTED_LINES.contains(lineId)) continue;
            for (String direction : directionsOf(lineId)) {
                for (DirectedRailPattern p : directed(lineId, direction)) {
                    for (RailPattern.Stop stop : p.stops()) {
                        if (SubwayPatternStationMatcher.match(stop.name(), stop.lat(), stop.lon(),
                                stationsByLine.get(lineId)).isEmpty()) {
                            unmatched.computeIfAbsent(lineId, k -> new TreeSet<>()).add(stop.name());
                        }
                    }
                }
            }
        }
        assertThat(unmatched).as("역에 못 이은 정차").isEqualTo(KNOWN_UNMATCHED_STOPS);
    }

    @Test
    @DisplayName("좌표로 대체한 연결은 이름이 달라도 같은 역이다 — 틀린 역에 붙지 않는다")
    void nearestFallbackNeverPicksAnotherStation() {
        List<String> fallbacks = new ArrayList<>();
        for (String lineId : stationsByLine.keySet()) {
            for (String direction : directionsOf(lineId)) {
                for (DirectedRailPattern p : directed(lineId, direction)) {
                    for (RailPattern.Stop stop : p.stops()) {
                        SubwayPatternStationMatcher.match(stop.name(), stop.lat(), stop.lon(), stationsByLine.get(lineId))
                                .filter(c -> !Objects.equals(NORMALIZER.normalize(c.stationName()),
                                        NORMALIZER.normalize(stop.name())))
                                .ifPresent(c -> fallbacks.add(lineId + " " + stop.name() + " → " + c.stationName()));
                    }
                }
            }
        }
        assertThat(new TreeSet<>(fallbacks)).as("이름이 다른데 좌표로 붙은 연결")
                .containsExactlyInAnyOrderElementsOf(KNOWN_RENAMES);
    }

    @Test
    @DisplayName("한 노선 안에서 서로 다른 정차가 같은 역에 붙지 않는다")
    void distinctStopsNeverShareAStation() {
        List<String> clashes = new ArrayList<>();
        for (OtpRailRoute route : routes) {
            String lineId = SubwayRoutePatternAssembler.LINE_IDS.get(route.shortName());
            if (lineId == null || !stationsByLine.containsKey(lineId)) continue;
            Map<String, Set<String>> namesByStation = new HashMap<>();
            for (RailPattern p : patternsByRoute.get(route.gtfsId())) {
                for (RailPattern.Stop stop : p.stops()) {
                    SubwayPatternStationMatcher.match(stop.name(), stop.lat(), stop.lon(), stationsByLine.get(lineId))
                            .ifPresent(c -> namesByStation.computeIfAbsent(c.stationId(), k -> new TreeSet<>())
                                    .add(stop.name()));
                }
            }
            namesByStation.forEach((station, names) -> {
                if (names.size() > 1) clashes.add(route.gtfsId() + " " + station + " ← " + names);
            });
        }
        assertThat(new TreeSet<>(clashes)).as("두 정차가 한 역에 붙었다").isEmpty();
    }

    /** 정차를 역 마스터 이름(정규화)으로. 못 이으면 OTP 이름. 개명된 역(당고개→불암산)을 실시간 표기로 맞춘다. */
    private static String stationName(String lineId, RailPattern.Stop stop) {
        return SubwayPatternStationMatcher.match(stop.name(), stop.lat(), stop.lon(), stationsByLine.get(lineId))
                .map(c -> NORMALIZER.normalize(c.stationName()))
                .orElseGet(() -> NORMALIZER.normalize(stop.name()));
    }

    private static int total(String lineId, String direction) {
        return destinations.stream()
                .filter(x -> lineId.equals(x.path("lineId").asText()) && direction.equals(x.path("direction").asText()))
                .mapToInt(x -> x.path("n").asInt()).sum();
    }

    private static Set<String> directionsOf(String lineId) {
        return "1002".equals(lineId) ? Set.of("내선", "외선") : Set.of("상행", "하행");
    }

    private static List<DirectedRailPattern> directed(String lineId, String direction) {
        List<DirectedRailPattern> out = new ArrayList<>();
        for (OtpRailRoute route : SubwayRoutePatternAssembler.selectRoutes(routes, lineId, direction)) {
            out.addAll(SubwayRoutePatternAssembler.orient(route, patternsByRoute.get(route.gtfsId()), direction));
        }
        return out;
    }

    private static JsonNode read(String path) throws IOException {
        return OM.readTree(new ClassPathResource(path).getContentAsByteArray());
    }
}
