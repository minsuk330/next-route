package watoo.grd.nextroute.application.subway.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 승차역부터 한 방향으로 남은 역을 패턴에서 뽑는다(#283). I/O 없음.
 *
 * <h2>규칙</h2>
 * <ol>
 *   <li><b>꼬리</b>: 승차역을 지나는 패턴마다 승차역 다음부터 끝까지. 순환선이면 승차역이 다시
 *       나오기 직전까지 — 2호선 패턴 하나가 여러 바퀴를 돌기 때문이다(최대 517정차).</li>
 *   <li><b>행선지</b>(있으면): 순환선은 <b>자르지 않고 본선·지선 고르기에만</b> 쓴다. 실시간 2호선
 *       행선지는 거의 다 "성수"라 성수에서 자르면 한 바퀴가 끊긴다. 그 밖은 행선지가 든 꼬리를
 *       골라 행선지에서 자른다.</li>
 *   <li><b>공통 구간</b>(행선지가 없거나 못 맞췄으면): 다른 꼬리의 부분수열인 꼬리(회차·급행 통과
 *       패턴)를 지우고, 남은 꼬리들의 최장 공통 앞부분까지. 갈라지면 분기점에서 멈추고 갈래를 준다.</li>
 * </ol>
 *
 * <p>역 비교는 {@link Stop#key()} 로 한다. 역 ID 로 이었으면 역 ID, 못 이었으면 이름이다.
 */
public final class StationsAheadCalculator {

    /** 같은 노선 trip 합의 이 비율 미만 패턴은 공통 구간을 깎지 못한다(임시·회송 패턴). */
    static final double MINOR_PATTERN_SHARE = 0.05;

    private static final String BRANCH_LINE = "지선";

    /**
     * 순환 본선 갈래의 행선지 값(#292). 본선 갈래는 한 바퀴 돌아 승차역 바로 앞(신도림이면 대림)에서
     * 끝나는데, 그 역 이름을 주면 앱의 "○○ 방면" 표시가 <b>반대 방향</b>으로 읽힌다. 다시 보내면 본선
     * 한 바퀴가 나온다.
     */
    public static final String LOOP_DESTINATION = "순환";

    /** 실시간 2호선 행선지 표기 중 지선을 뜻하는 것. 역 이름이 아니다. */
    static final Set<String> BRANCH_DESTINATIONS = Set.of("성수지선", "신도림지선");

    private static final DestinationNormalizer NORMALIZER = new DestinationNormalizer();

    private StationsAheadCalculator() {
    }

    public enum EndReason { TERMINAL, BRANCH, LOOP }

    /**
     * @param key       비교 키. 역 ID 로 이었으면 역 ID, 못 이었으면 정규화 이름
     * @param stationId 역 ID. 못 이었으면 null
     * @param name      표시 이름
     */
    public record Stop(String key, String stationId, String name, Integer arrivalSec, Integer departureSec) {
    }

    public record Pattern(String routeGtfsId, String routeLongName, boolean loop, int tripCount, List<Stop> stops) {
    }

    public record Ahead(Stop stop, int stopsAway, Integer minutes) {
    }

    /**
     * @param destinationName 다시 부를 때 {@code destination} 값. 순환 본선이면 {@link #LOOP_DESTINATION}
     * @param kind            그 갈래가 어떻게 끝나는지. {@link EndReason#LOOP} 이면 순환 본선이다
     */
    public record Branch(String destinationName, String nextStationName, EndReason kind) {
    }

    public record Result(List<Ahead> stations, EndReason endReason, List<Branch> branches,
                         boolean destinationApplied) {
    }

    /** 승차역이 어느 패턴에도 없는지. 부르는 쪽이 "노선에 없는 역"으로 따로 답하려고 쓴다. */
    public static boolean boardsAnywhere(List<Pattern> patterns, String boardKey) {
        return patterns.stream().anyMatch(p -> indexOf(p.stops(), boardKey, 0) >= 0);
    }

    public static Result calculate(List<Pattern> patterns, String boardKey, String destination) {
        List<Tail> tails = tails(patterns, boardKey);
        if (tails.isEmpty()) {
            return new Result(List.of(), EndReason.TERMINAL, List.of(), false);
        }
        boolean loop = tails.stream().anyMatch(t -> t.pattern().loop());
        String target = destination == null || destination.isBlank() ? null : destination.trim();

        if (target != null && loop) {
            List<Tail> chosen = chooseLoopRoute(tails, target);
            if (chosen != null) {
                return common(chosen, true);
            }
        } else if (target != null) {
            String key = NORMALIZER.normalize(target);
            Tail best = tails.stream()
                    .filter(t -> indexOfName(t.stops(), key) >= 0)
                    .max(Comparator.comparingInt(t -> t.pattern().tripCount()))
                    .orElse(null);
            if (best != null) {
                List<Stop> cut = best.stops().subList(0, indexOfName(best.stops(), key) + 1);
                return new Result(ahead(cut, best), EndReason.TERMINAL, List.of(), true);
            }
        }
        return common(tails, false);
    }

    /**
     * 순환선에서 행선지로 본선·지선을 고른다. 고를 근거가 없으면 null.
     *
     * <p>지선 꼬리에만 있는 역(까치산·신설동)이나 지선 표기(성수지선)면 지선, 그 밖(성수 포함)은 본선이다.
     */
    private static List<Tail> chooseLoopRoute(List<Tail> tails, String destination) {
        List<Tail> branchLine = tails.stream().filter(StationsAheadCalculator::onBranchLine).toList();
        List<Tail> mainLine = tails.stream().filter(t -> !onBranchLine(t)).toList();
        if (branchLine.isEmpty() || mainLine.isEmpty()) {
            return null;
        }
        if (LOOP_DESTINATION.equals(destination)) {
            return mainLine;
        }
        String key = NORMALIZER.normalize(destination);
        boolean branchOnly = BRANCH_DESTINATIONS.contains(destination)
                || (branchLine.stream().anyMatch(t -> indexOfName(t.stops(), key) >= 0)
                && mainLine.stream().noneMatch(t -> indexOfName(t.stops(), key) >= 0));
        return branchOnly ? branchLine : mainLine;
    }

    private static boolean onBranchLine(Tail tail) {
        String name = tail.pattern().routeLongName();
        return name != null && name.contains(BRANCH_LINE);
    }

    private static Result common(List<Tail> candidates, boolean destinationApplied) {
        List<Tail> maximal = withoutSubsequences(candidates);
        List<Tail> major = withoutMinor(maximal);

        int length = commonPrefixLength(major);
        Tail representative = major.stream()
                .filter(t -> t.stops().size() >= length)
                .max(Comparator.comparingInt(t -> t.pattern().tripCount()))
                .orElseThrow();
        List<Stop> prefix = representative.stops().subList(0, length);

        boolean diverges = major.stream().anyMatch(t -> t.stops().size() > length);
        if (!diverges) {
            // 한 바퀴를 돌아 승차역 앞에서 끊긴 꼬리만 LOOP 다. 같은 순환 노선이라도 지선(신도림→까치산)은 종착이 있다.
            return new Result(ahead(prefix, representative),
                    representative.lapped() && prefix.size() == representative.stops().size()
                            ? EndReason.LOOP : EndReason.TERMINAL,
                    List.of(), destinationApplied);
        }
        return new Result(ahead(prefix, representative), EndReason.BRANCH, branches(major, length),
                destinationApplied);
    }

    /**
     * 갈래마다 하나: 앱이 행선지로 다시 보낼 값과 분기 다음 역. 다시 보낼 값은 그 갈래 대표 꼬리의 끝 역이고,
     * 한 바퀴 도는 순환 본선이면 {@link #LOOP_DESTINATION} 이다.
     */
    private static List<Branch> branches(List<Tail> tails, int length) {
        Map<String, Tail> byNext = new LinkedHashMap<>();
        tails.stream()
                .filter(t -> t.stops().size() > length)
                .sorted(Comparator.comparingInt((Tail t) -> t.pattern().tripCount()).reversed())
                .forEach(t -> byNext.putIfAbsent(t.stops().get(length).key(), t));
        return byNext.values().stream()
                .map(t -> t.lapped()
                        ? new Branch(LOOP_DESTINATION, t.stops().get(length).name(), EndReason.LOOP)
                        : new Branch(t.stops().get(t.stops().size() - 1).name(), t.stops().get(length).name(),
                                EndReason.TERMINAL))
                .toList();
    }

    private static List<Ahead> ahead(List<Stop> stops, Tail source) {
        List<Ahead> out = new ArrayList<>(stops.size());
        for (int i = 0; i < stops.size(); i++) {
            out.add(new Ahead(stops.get(i), i + 1, minutes(source.boardDepartureSec(), stops.get(i).arrivalSec())));
        }
        return out;
    }

    /**
     * 승차역 출발부터 그 역 도착까지 분. OTP 시각은 서비스일 기준이라 자정을 넘겨도 줄지 않는다 —
     * 음수면 시각이 어긋난 것이라 모름으로 둔다.
     */
    private static Integer minutes(Integer boardDeparture, Integer arrival) {
        if (boardDeparture == null || arrival == null || arrival < boardDeparture) {
            return null;
        }
        return Math.round((arrival - boardDeparture) / 60f);
    }

    private static List<Tail> tails(List<Pattern> patterns, String boardKey) {
        List<Tail> tails = new ArrayList<>();
        for (Pattern pattern : patterns) {
            List<Stop> stops = pattern.stops();
            int board = indexOf(stops, boardKey, 0);
            if (board < 0) {
                continue;
            }
            int end = stops.size();
            boolean lapped = false;
            if (pattern.loop()) {
                int again = indexOf(stops, boardKey, board + 1);
                if (again >= 0) {
                    end = again;
                    lapped = true;
                }
            }
            List<Stop> tail = stops.subList(board + 1, end);
            if (!tail.isEmpty()) {
                tails.add(new Tail(pattern, List.copyOf(tail), stops.get(board).departureSec(), lapped));
            }
        }
        return tails;
    }

    /** 다른 꼬리의 부분수열(앞부분 포함)인 꼬리를 지운다. 같은 꼬리는 trip 이 많은 쪽 하나만 남긴다. */
    private static List<Tail> withoutSubsequences(List<Tail> tails) {
        List<Tail> sorted = new ArrayList<>(tails);
        sorted.sort(Comparator.comparingInt((Tail t) -> t.stops().size()).reversed()
                .thenComparing(Comparator.comparingInt((Tail t) -> t.pattern().tripCount()).reversed()));
        List<Tail> kept = new ArrayList<>();
        for (Tail tail : sorted) {
            if (kept.stream().noneMatch(k -> isSubsequence(keys(tail), keys(k)))) {
                kept.add(tail);
            }
        }
        return kept;
    }

    /** 같은 노선 trip 합 대비 적은 패턴을 뺀다. 다 빠지면 원래대로 둔다. */
    private static List<Tail> withoutMinor(List<Tail> tails) {
        Map<String, Integer> tripsByRoute = new HashMap<>();
        tails.forEach(t -> tripsByRoute.merge(t.pattern().routeGtfsId(), t.pattern().tripCount(), Integer::sum));
        List<Tail> major = tails.stream()
                .filter(t -> t.pattern().tripCount()
                        >= MINOR_PATTERN_SHARE * tripsByRoute.get(t.pattern().routeGtfsId()))
                .toList();
        return major.isEmpty() ? tails : major;
    }

    private static int commonPrefixLength(List<Tail> tails) {
        int length = 0;
        while (true) {
            String key = null;
            for (Tail tail : tails) {
                if (tail.stops().size() <= length) {
                    return length;
                }
                String current = tail.stops().get(length).key();
                if (key == null) {
                    key = current;
                } else if (!key.equals(current)) {
                    return length;
                }
            }
            length++;
        }
    }

    private static boolean isSubsequence(List<String> small, List<String> big) {
        if (small.size() > big.size()) {
            return false;
        }
        int i = 0;
        for (String key : big) {
            if (i < small.size() && small.get(i).equals(key)) {
                i++;
            }
        }
        return i == small.size();
    }

    private static List<String> keys(Tail tail) {
        return tail.stops().stream().map(Stop::key).toList();
    }

    private static int indexOf(List<Stop> stops, String key, int from) {
        for (int i = from; i < stops.size(); i++) {
            if (Objects.equals(key, stops.get(i).key())) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfName(List<Stop> stops, String normalizedName) {
        if (normalizedName == null) {
            return -1;
        }
        for (int i = 0; i < stops.size(); i++) {
            if (normalizedName.equals(NORMALIZER.normalize(stops.get(i).name()))) {
                return i;
            }
        }
        return -1;
    }

    /** @param lapped 순환선에서 승차역이 다시 나와 끊겼는지(=한 바퀴) */
    private record Tail(Pattern pattern, List<Stop> stops, Integer boardDepartureSec, boolean lapped) {
    }
}
