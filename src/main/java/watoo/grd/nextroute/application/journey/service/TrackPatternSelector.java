package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.PatternStop;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPattern;
import watoo.grd.nextroute.application.subway.service.SubwayDirectionNormalizer;
import watoo.grd.nextroute.application.route.routing.RoutingMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 단독 추적(#265)에서 정류장 하나를 지나는 OTP 패턴들을 "고를 수 있는 방향"과 "타는 구간"으로 줄인다. 순수 함수다.
 *
 * <h2>왜 패턴을 그대로 보여 주지 않는가</h2>
 * 2호선 강남역 하나에 패턴이 49개다(2026-10-07 실측). 패턴 하나가 여러 바퀴를 돌고(같은 역 최대 12회), 회차·단축
 * 운행이 각자 패턴이다. 사용자가 고를 것은 "어느 노선의 어느 쪽"이지 패턴이 아니다.
 *
 * <h2>규칙</h2>
 * <ul>
 *   <li><b>한 바퀴</b>: 승차 정류장의 <b>첫 등장</b> 다음부터, 승차 정류장이 다시 나오기 직전(순환선) 또는 패턴 끝까지가
 *       갈 수 있는 구간이다. 한 바퀴 넘게 타는 것은 고르지 않는다.</li>
 *   <li><b>가지</b>: 같은 노선에서 남은 구간이 다른 패턴 남은 구간의 앞부분이면(단축 운행) 빼고, 완전히 같으면 trip 이
 *       많은 쪽만 남긴다. 남는 것이 서로 다른 가지(분기·반대 방향)다.</li>
 *   <li><b>방향 이름</b>: headsign → 순환이면 지하철 방향 라벨(내선·외선) → "{종점} 방면". 실측상 headsign 은 버스·지하철
 *       모두 비어 있다(TAGO {@code directionId=-1}).</li>
 * </ul>
 */
public final class TrackPatternSelector {

    private TrackPatternSelector() {
    }

    /**
     * 사용자에게 보일 방향 하나.
     *
     * @param remaining 승차 다음부터 한 바퀴 안의 정류장(하차 후보)
     * @param circular  승차 정류장이 패턴에 다시 나와 거기서 잘랐다
     */
    public record Branch(TransitPattern pattern, List<PatternStop> remaining, boolean circular,
                         String directionLabel) {

        public String terminalName() {
            return remaining.isEmpty() ? null : remaining.get(remaining.size() - 1).name();
        }
    }

    /**
     * 시작할 구간.
     *
     * @param boardIndex  패턴 안 승차 위치(첫 등장)
     * @param alightIndex 패턴 안 하차 위치(승차 뒤 한 바퀴 안의 첫 등장)
     */
    public record Selection(TransitPattern pattern, int boardIndex, int alightIndex) {
    }

    /** 승차 정류장에서 고를 수 있는 방향들. 노선 → 남은 구간 길이 내림차순. */
    public static List<Branch> branches(String boardStopId, List<TransitPattern> patterns) {
        Map<String, List<Branch>> byRoute = new LinkedHashMap<>();
        for (TransitPattern pattern : patterns) {
            Lap lap = lap(pattern, boardStopId);
            if (lap == null || lap.remaining().isEmpty()) {
                continue;
            }
            byRoute.computeIfAbsent(pattern.route().gtfsId(), k -> new ArrayList<>())
                    .add(new Branch(pattern, lap.remaining(), lap.circular(), null));
        }
        List<Branch> result = new ArrayList<>();
        for (List<Branch> routeBranches : byRoute.values()) {
            for (Branch branch : distinct(routeBranches)) {
                result.add(new Branch(branch.pattern(), branch.remaining(), branch.circular(),
                        directionLabel(branch)));
            }
        }
        return result;
    }

    /** 고른 노선의 방향 하나. 패턴 코드가 그 노선·승차를 지나면 그것, 아니면(낡은 코드) 가장 긴 가지. */
    public static Optional<Branch> branchFor(String routeGtfsId, String boardStopId, String patternCode,
                                             List<TransitPattern> patterns) {
        List<Branch> routeBranches = branches(boardStopId, patterns).stream()
                .filter(b -> routeGtfsId.equals(b.pattern().route().gtfsId()))
                .toList();
        if (patternCode != null) {
            for (TransitPattern pattern : patterns) {
                if (patternCode.equals(pattern.code()) && routeGtfsId.equals(pattern.route().gtfsId())) {
                    Lap lap = lap(pattern, boardStopId);
                    if (lap != null && !lap.remaining().isEmpty()) {
                        Branch raw = new Branch(pattern, lap.remaining(), lap.circular(), null);
                        return Optional.of(new Branch(pattern, raw.remaining(), raw.circular(), directionLabel(raw)));
                    }
                }
            }
        }
        return routeBranches.stream().findFirst();
    }

    /**
     * 승차 → 하차 구간을 가진 패턴을 고른다.
     *
     * <p>패턴 코드가 있고 그 패턴이 승차 다음 한 바퀴 안에 하차를 지나면 그것. 아니면 그 노선 패턴 중 구간이 가장 짧은 것,
     * 같으면 trip 이 많은 것. 코드는 그래프를 다시 빌드하면 바뀌므로 낡아도 노선으로 다시 찾는다.
     */
    public static Optional<Selection> select(String routeGtfsId, String boardStopId, String alightStopId,
                                             String patternCode, List<TransitPattern> patterns) {
        List<Selection> candidates = new ArrayList<>();
        for (TransitPattern pattern : patterns) {
            if (!routeGtfsId.equals(pattern.route().gtfsId())) {
                continue;
            }
            Lap lap = lap(pattern, boardStopId);
            if (lap == null) {
                continue;
            }
            for (int k = 0; k < lap.remaining().size(); k++) {
                if (alightStopId.equals(lap.remaining().get(k).gtfsId())) {
                    candidates.add(new Selection(pattern, lap.boardIndex(), lap.boardIndex() + 1 + k));
                    break;
                }
            }
        }
        if (patternCode != null) {
            Optional<Selection> exact = candidates.stream()
                    .filter(c -> patternCode.equals(c.pattern().code()))
                    .findFirst();
            if (exact.isPresent()) {
                return exact;
            }
        }
        return candidates.stream().min(Comparator
                .comparingInt((Selection c) -> c.alightIndex() - c.boardIndex())
                .thenComparing(Comparator.comparingInt((Selection c) -> c.pattern().tripCount()).reversed())
                .thenComparing(c -> c.pattern().code()));
    }

    /** 패턴 정류장 id 목록에서 승차 첫 등장과 한 바퀴 끝. 승차가 없으면 null. */
    static Lap lap(TransitPattern pattern, String boardStopId) {
        List<PatternStop> stops = pattern.stops();
        int board = -1;
        for (int i = 0; i < stops.size(); i++) {
            if (boardStopId.equals(stops.get(i).gtfsId())) {
                board = i;
                break;
            }
        }
        if (board < 0) {
            return null;
        }
        int end = stops.size();
        boolean circular = false;
        for (int j = board + 1; j < stops.size(); j++) {
            if (boardStopId.equals(stops.get(j).gtfsId())) {
                end = j;
                circular = true;
                break;
            }
        }
        return new Lap(board, List.copyOf(stops.subList(board + 1, end)), circular);
    }

    record Lap(int boardIndex, List<PatternStop> remaining, boolean circular) {
    }

    /** 같은 노선 안에서 다른 가지의 앞부분(단축 운행)이거나 같은 가지는 뺀다. 남은 구간이 긴 순. */
    private static List<Branch> distinct(List<Branch> branches) {
        List<Branch> sorted = new ArrayList<>(branches);
        sorted.sort(Comparator.comparingInt((Branch b) -> b.remaining().size()).reversed()
                .thenComparing(Comparator.comparingInt((Branch b) -> b.pattern().tripCount()).reversed())
                .thenComparing(b -> b.pattern().code()));
        List<Branch> kept = new ArrayList<>();
        for (Branch candidate : sorted) {
            boolean covered = kept.stream().anyMatch(k -> isPrefix(candidate.remaining(), k.remaining()));
            if (!covered) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    private static boolean isPrefix(List<PatternStop> shorter, List<PatternStop> longer) {
        if (shorter.size() > longer.size()) {
            return false;
        }
        for (int i = 0; i < shorter.size(); i++) {
            if (!shorter.get(i).gtfsId().equals(longer.get(i).gtfsId())) {
                return false;
            }
        }
        return true;
    }

    private static String directionLabel(Branch branch) {
        String headsign = branch.pattern().headsign();
        if (headsign != null && !headsign.isBlank()) {
            return headsign;
        }
        RoutingMode mode = branch.pattern().route().mode();
        if (branch.circular() && (mode == RoutingMode.SUBWAY || mode == RoutingMode.RAIL)) {
            String label = SubwayDirectionNormalizer.labelFrom(branch.pattern().route().longName());
            if (label != null) {
                return label;
            }
        }
        return branch.terminalName() + " 방면";
    }
}
