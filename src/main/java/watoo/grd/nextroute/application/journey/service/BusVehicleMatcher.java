package watoo.grd.nextroute.application.journey.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 승차 시각과 승차 정류장 출발 사건을 맞대어 탄 차를 고른다(#265). 순수 함수다.
 *
 * <ul>
 *   <li>창(±{@code window}) 안 출발 1대 → 그 차</li>
 *   <li>2대 이상 → 승차 시각과 가장 가까운 것과 두 번째의 시각 차가 {@code ambiguityGap} 이상일 때만 그 차</li>
 *   <li>0대 → 없음(부르는 쪽이 조금 더 기다렸다 다시 묻고, 그래도 없으면 포기)</li>
 * </ul>
 *
 * <p>위치 갱신 해상도가 20~30초라(배차 5~15분) 승차 순간 하나로 정한다 — 뒤에 따라붙는 확정 단계가 없다. 그래서 갈리지
 * 않으면 고르지 않는다(붙어 오는 두 대는 포기).
 */
public final class BusVehicleMatcher {

    private BusVehicleMatcher() {
    }

    /** 승차 정류장을 떠난 차 하나. */
    public record Departure(String vehicleId, String plateNo, Instant departedAt) {
    }

    public enum Kind { MATCHED, NONE, AMBIGUOUS }

    public record Result(Kind kind, Departure departure) {

        static Result none() {
            return new Result(Kind.NONE, null);
        }
    }

    public static Result match(Instant boardedAt, List<Departure> departures, Duration window, Duration ambiguityGap) {
        // 같은 차가 여러 번 잡혔으면(재조회) 가장 이른 출발만 본다.
        Map<String, Departure> byVehicle = departures.stream()
                .filter(d -> !d.departedAt().isBefore(boardedAt.minus(window))
                        && !d.departedAt().isAfter(boardedAt.plus(window)))
                .collect(Collectors.toMap(Departure::vehicleId, Function.identity(),
                        (a, b) -> a.departedAt().isBefore(b.departedAt()) ? a : b));
        List<Departure> sorted = byVehicle.values().stream()
                .sorted(Comparator.comparingLong(d -> distance(boardedAt, d)))
                .toList();
        if (sorted.isEmpty()) {
            return Result.none();
        }
        if (sorted.size() == 1) {
            return new Result(Kind.MATCHED, sorted.get(0));
        }
        long gap = distance(boardedAt, sorted.get(1)) - distance(boardedAt, sorted.get(0));
        return gap >= ambiguityGap.toMillis()
                ? new Result(Kind.MATCHED, sorted.get(0))
                : new Result(Kind.AMBIGUOUS, null);
    }

    private static long distance(Instant boardedAt, Departure departure) {
        return Math.abs(Duration.between(boardedAt, departure.departedAt()).toMillis());
    }
}
