package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.application.journey.dto.TrackViews;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.PatternStop;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPattern;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.routing.RoutingMode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 근처 정류장의 대표 진행 방향(#538). 순수 함수다.
 *
 * <h2>규칙</h2>
 * <ol>
 *   <li>묶음 정류장마다 {@link TrackPatternSelector#branches} 로 방향별 분기를 만든다(UNSUPPORTED·WALK 제외).</li>
 *   <li>분기 방위각 = 승차 정류장 → {@code remaining[0]}. 다른 분기가 {@link #TOLERANCE_DEGREES} 안에 가장 많이 모인
 *       분기가 기준이고(동률이면 노선 이름 사전순), 기준에서 그 안인 분기가 대표 방향이다.</li>
 *   <li>{@code patternCodes} = 대표 방향 분기의 패턴 코드. 다음 정류장은 대표 방향 안에서 다음 정류장 정체 key
 *       ({@link TrackStopSiblings#groupKey}, 없으면 gtfsId) 다수결, 그 key 의 노선 이름 사전순 첫 분기.</li>
 *   <li>좌표가 하나도 없으면 다음 정류장 key 다수결로 대신하고 방위각은 null.</li>
 * </ol>
 *
 * <h2>왜 다음 정류장 key 가 아니라 방위각으로 묶는가</h2>
 * 같은 쪽으로 가도 노선마다 다음 정류장이 다르다 — 강남역9번출구에서 서울 노선은 서초동진흥아파트(249°)·진흥아파트(272°),
 * 경기 노선은 서초푸르지오써밋(317°)으로 간다. key 다수결이면 7/5/3 으로 갈려 15개 중 7개만 대표가 된다.
 * 운영 6곳 754분기 실측(2026-11-03)에서 key 다수결 72%, ±60° 방위각 91% 가 대표에 든다. 빠지는 것은 교차로에서
 * 실제로 꺾는 노선이다.
 */
public final class TrackStopHeading {

    /** 운영 실측에서 ±45° 90%, ±60° 91%, 길 건너편 판정은 같았다 → 좌표 오차에 여유 있는 쪽. */
    static final double TOLERANCE_DEGREES = 60;

    private static final Comparator<Candidate> BY_ROUTE_NAME = Comparator
            .comparing((Candidate c) -> c.branch().pattern().route().shortName(),
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(c -> c.branch().pattern().code(), Comparator.nullsLast(Comparator.naturalOrder()));

    private TrackStopHeading() {
    }

    /** 묶음 정류장 하나와 그 정류장을 지나는 패턴. */
    public record BoardPatterns(TransitStop board, List<TransitPattern> patterns) {
    }

    private record Candidate(TrackPatternSelector.Branch branch, String nextKey, Double bearing) {
    }

    /**
     * @param origin 응답에 나가는 대표 정류장. 방위각 시작점
     * @param group  대표를 포함한 묶음 정류장들
     * @return 분기가 없으면 empty
     */
    public static Optional<TrackViews.Heading> of(TransitStop origin, List<BoardPatterns> group) {
        List<Candidate> candidates = new ArrayList<>();
        for (BoardPatterns member : group) {
            TransitStop board = member.board();
            for (TrackPatternSelector.Branch branch : TrackPatternSelector.branches(board.gtfsId(), member.patterns())) {
                RoutingMode mode = branch.pattern().route().mode();
                if (mode == RoutingMode.UNSUPPORTED || mode == RoutingMode.WALK) {
                    continue;
                }
                PatternStop next = branch.remaining().get(0);
                candidates.add(new Candidate(branch, nextKey(next),
                        bearing(board.lat(), board.lon(), next.lat(), next.lon())));
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<Candidate> direction = representativeDirection(candidates);
        Candidate pick = byNextStop(direction);
        PatternStop next = pick.branch().remaining().get(0);
        Double bearing = pick.bearing() == null ? null
                : round(bearing(origin.lat(), origin.lon(), next.lat(), next.lon()));
        TreeSet<String> codes = new TreeSet<>();
        direction.forEach(c -> codes.add(c.branch().pattern().code()));
        return Optional.of(new TrackViews.Heading(List.copyOf(codes),
                nextStopNames(pick.branch().remaining()), bearing));
    }

    /** 다음 정류장 이름 앞 1~2개. 두 이름이 같으면 하나. */
    public static List<String> nextStopNames(List<PatternStop> remaining) {
        List<String> names = new ArrayList<>(2);
        for (PatternStop stop : remaining.subList(0, Math.min(2, remaining.size()))) {
            if (stop.name() != null && !names.contains(stop.name())) {
                names.add(stop.name());
            }
        }
        return List.copyOf(names);
    }

    /** 방위각(북 0, 시계방향, 0~360). 끝점 좌표가 없으면 null. */
    static Double bearing(double lat1, double lon1, Double lat2, Double lon2) {
        if (lat2 == null || lon2 == null) {
            return null;
        }
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    static double angleBetween(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return Math.min(d, 360 - d);
    }

    private static List<Candidate> representativeDirection(List<Candidate> candidates) {
        List<Candidate> located = candidates.stream().filter(c -> c.bearing() != null).toList();
        if (located.isEmpty()) {
            // 좌표가 없으면 다음 정류장 key 가 같은 것끼리
            String key = byNextStop(candidates).nextKey();
            return candidates.stream().filter(c -> c.nextKey().equals(key)).toList();
        }
        Candidate seed = located.stream()
                .min(Comparator.comparingLong((Candidate c) -> -neighbours(c, located)).thenComparing(BY_ROUTE_NAME))
                .orElseThrow();
        return located.stream()
                .filter(c -> angleBetween(c.bearing(), seed.bearing()) <= TOLERANCE_DEGREES)
                .toList();
    }

    private static long neighbours(Candidate c, List<Candidate> located) {
        return located.stream().filter(o -> angleBetween(o.bearing(), c.bearing()) <= TOLERANCE_DEGREES).count();
    }

    /** 다음 정류장 key 다수결, 동률이면 그 key 분기 중 노선 이름 사전순 첫 분기가 앞서는 key. 그 key 의 첫 분기. */
    private static Candidate byNextStop(List<Candidate> candidates) {
        Map<String, List<Candidate>> byKey = new LinkedHashMap<>();
        candidates.forEach(c -> byKey.computeIfAbsent(c.nextKey(), k -> new ArrayList<>()).add(c));
        return byKey.values().stream()
                .map(list -> list.stream().min(BY_ROUTE_NAME).map(first -> Map.entry(list.size(), first)).orElseThrow())
                .min(Comparator.comparingInt((Map.Entry<Integer, Candidate> e) -> -e.getKey())
                        .thenComparing(Map.Entry::getValue, BY_ROUTE_NAME))
                .orElseThrow()
                .getValue();
    }

    private static String nextKey(PatternStop next) {
        String key = TrackStopSiblings.groupKey(next.gtfsId());
        return key != null ? key : next.gtfsId();
    }

    private static double round(double degrees) {
        double rounded = Math.round(degrees * 10) / 10.0;
        return rounded >= 360 ? 0 : rounded;
    }
}
