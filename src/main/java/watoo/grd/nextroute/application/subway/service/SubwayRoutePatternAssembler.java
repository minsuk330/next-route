package watoo.grd.nextroute.application.subway.service;

import watoo.grd.nextroute.application.subway.dto.DirectedRailPattern;
import watoo.grd.nextroute.application.subway.dto.OtpRailRoute;
import watoo.grd.nextroute.application.subway.dto.RailPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OTP 노선을 내부 line_id·실시간 방향 라벨로 골라 맞춘다(#283). I/O 없음.
 *
 * <h2>OTP 라벨을 그대로 믿지 않는 이유</h2>
 *
 * <p>feed 의 방향 라벨이 실시간과 <b>반대인 노선이 실측으로 넷</b>이고, 방향이 없는 노선이 하나다
 * (2026-10-07, 실시간 행선지 전수 대조). 하나라도 놓치면 그 노선만 <b>반대 방향 역 목록</b>이
 * 조용히 나간다.
 * <ul>
 *   <li>경의중앙선 서울역 노선({@code KJ-2}): {@code <하행>} 이 서울→문산인데 실시간은 문산행을
 *       {@code 상행} 이라 부른다.</li>
 *   <li>2호선 성수지선({@code 02-2}): {@code <내선>} 이 신설동→성수인데 실시간 내선 행선지는 신설동이다.
 *       같은 2호선이라도 신정지선({@code 02-3})은 실시간과 같다.</li>
 *   <li>9호선({@code 09-*}): {@code <하행>} 이 개화→중앙보훈병원인데 실시간 하행 행선지는 개화다.</li>
 *   <li>공항철도({@code AP-1}): {@code <상행>} 이 인천공항2터미널→서울인데 실시간 상행 행선지는
 *       인천공항2터미널이다.</li>
 *   <li>6호선: 노선이 하나뿐이고 방향이 없다(응암 순환). 실시간은 응암까지 가는 쪽이 상행
 *       (행선지 {@code 응암순환(상선)}), 응암부터 순환을 돌아 나오는 쪽이 하행이다 → 패턴을
 *       응암 첫 등장에서 둘로 가른다.</li>
 * </ul>
 * 실시간 행선지 대조 커버리지 테스트({@code SubwayPatternCoverageTest})가 이 둘을 지킨다.
 *
 * <h2>급행 노선은 뺀다</h2>
 *
 * <p>{@code 서울9호선(급행)}, {@code 서울1호선(용산-동인천급행)} 처럼 별도 노선으로 오는 급행을
 * 섞으면 "남은 역" 의 공통 구간이 급행이 서는 역만큼 줄어든다. 이번 범위는 일반 열차 기준이다.
 */
public final class SubwayRoutePatternAssembler {

    /**
     * feed {@code shortName} → 내부 line_id. <b>명시표</b>다.
     *
     * <p>{@link SubwayLineIdMapper} 처럼 숫자를 뽑아 읽으면 {@code 인천2호선} 이 2호선(1002)이
     * 된다. feed 에는 부산·대구·인천 노선이 같은 모양으로 섞여 있다.
     */
    static final Map<String, String> LINE_IDS = Map.ofEntries(
            Map.entry("서울1호선", "1001"),
            Map.entry("서울2호선", "1002"),
            Map.entry("서울3호선", "1003"),
            Map.entry("서울4호선", "1004"),
            Map.entry("서울5호선", "1005"),
            Map.entry("서울6호선", "1006"),
            Map.entry("서울7호선", "1007"),
            Map.entry("서울8호선", "1008"),
            Map.entry("서울9호선", "1009"),
            Map.entry("GTX-A", "1032"),
            Map.entry("경의중앙선", "1063"),
            Map.entry("공항철도", "1065"),
            Map.entry("경춘선", "1067"),
            Map.entry("수인분당선", "1075"),
            Map.entry("신분당선", "1077"),
            Map.entry("경강선", "1081"),
            Map.entry("우이신설경전철", "1092"),
            Map.entry("서해선", "1093"),
            Map.entry("신림선", "1094")
    );

    /** 라벨이 실시간과 반대인 노선. 근거는 클래스 주석. */
    static final Set<String> REVERSED_ROUTES = Set.of(
            "1:RR_ACC1_S-1-KJ-2U", "1:RR_ACC1_S-1-KJ-2D",
            "1:RR_ACC1_S-1-02-2I", "1:RR_ACC1_S-1-02-2O",
            "1:RR_ACC1_S-1-09-1U", "1:RR_ACC1_S-1-09-1D",
            "1:RR_ACC1_S-1-09-2U", "1:RR_ACC1_S-1-09-2D",
            "1:RR_ACC1_S-1-AP-1U", "1:RR_ACC1_S-1-AP-1D");

    /** 방향 없는 노선 → 패턴을 가를 역(정규화 이름). 그 역 첫 등장까지(포함)가 상행이다. */
    static final Map<String, String> SPLIT_ROUTES = Map.of(
            "1:RR_ACC1_S-1-06-00", "응암");

    private static final String UP = "상행";
    private static final String DOWN = "하행";
    private static final String EXPRESS = "급행";

    private static final DestinationNormalizer NORMALIZER = new DestinationNormalizer();

    private SubwayRoutePatternAssembler() {
    }

    /** 실시간 방향 값 공간인지. */
    public static boolean isKnownDirection(String direction) {
        return UP.equals(direction) || DOWN.equals(direction)
                || "내선".equals(direction) || "외선".equals(direction);
    }

    /** 이 line_id·방향의 노선(본선+지선, 급행 제외). 없으면 빈 목록. */
    public static List<OtpRailRoute> selectRoutes(List<OtpRailRoute> routes, String lineId, String direction) {
        List<OtpRailRoute> selected = new ArrayList<>();
        for (OtpRailRoute route : routes) {
            if (!lineId.equals(LINE_IDS.get(route.shortName()))) {
                continue;
            }
            if (route.longName() != null && route.longName().contains(EXPRESS)) {
                continue;
            }
            if (SPLIT_ROUTES.containsKey(route.gtfsId())) {
                if (UP.equals(direction) || DOWN.equals(direction)) {
                    selected.add(route);
                }
                continue;
            }
            if (direction.equals(realtimeLabel(route))) {
                selected.add(route);
            }
        }
        return selected;
    }

    /**
     * 노선의 패턴을 요청 방향으로 맞춘다. 가르는 노선이면 그 방향 쪽 절반만 남긴다.
     *
     * <p>가를 역이 없는 패턴(회차 구간만 도는 짧은 패턴)은 방향을 알 수 없어 뺀다. 그 구간은
     * 같은 노선의 긴 패턴이 이미 덮는다.
     */
    public static List<DirectedRailPattern> orient(OtpRailRoute route, List<RailPattern> patterns, String direction) {
        String pivot = SPLIT_ROUTES.get(route.gtfsId());
        boolean loop = isLoop(direction);
        List<DirectedRailPattern> directed = new ArrayList<>();
        for (RailPattern pattern : patterns) {
            List<RailPattern.Stop> stops = pattern.stops();
            if (pivot != null) {
                int at = firstIndexOf(stops, pivot);
                if (at < 0) {
                    continue;
                }
                stops = UP.equals(direction) ? stops.subList(0, at + 1) : stops.subList(at, stops.size());
            }
            if (stops.size() < 2) {
                continue;
            }
            directed.add(new DirectedRailPattern(
                    route.gtfsId(), route.longName(), loop, pattern.tripCount(), List.copyOf(stops)));
        }
        return directed;
    }

    /** OTP 라벨에 반전표를 적용한 실시간 라벨. 못 읽으면 null. */
    static String realtimeLabel(OtpRailRoute route) {
        String label = SubwayDirectionNormalizer.labelFrom(route.longName());
        if (label == null || !REVERSED_ROUTES.contains(route.gtfsId())) {
            return label;
        }
        return switch (label) {
            case UP -> DOWN;
            case DOWN -> UP;
            case "내선" -> "외선";
            case "외선" -> "내선";
            default -> label;
        };
    }

    private static boolean isLoop(String direction) {
        return "내선".equals(direction) || "외선".equals(direction);
    }

    private static int firstIndexOf(List<RailPattern.Stop> stops, String normalizedName) {
        for (int i = 0; i < stops.size(); i++) {
            if (normalizedName.equals(NORMALIZER.normalize(stops.get(i).name()))) {
                return i;
            }
        }
        return -1;
    }
}
