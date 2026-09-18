package watoo.grd.nextroute.application.route.routing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 대중교통 leg 를 실제 탑승점(board)부터 지정한 중간 하차점(X)까지로 자른 결과.
 *
 * <p>소요는 stopCall 시각의 순수 뺄셈이고, 지도선은 {@link #stops()} 좌표를 그대로 이어 그린다 —
 * 둘 다 폴리라인 디코드·투영이 없다(journey-reroute-nextstop PLAN PR-A 확정).
 * X 이후 정류장은 버린다.
 *
 * @param boardAt             leg 자신의 탑승 시각(첫 stop 의 최선 시각). 시각 없으면 null(지어내지 않음)
 * @param alightAt            X 하차 시각(X stop 의 최선 시각). 시각 없으면 null
 * @param rideDurationSeconds alightAt - boardAt(초). 둘 중 하나라도 없으면 null
 * @param stops               board..X 구간(양끝 포함), leg 원래 순서
 */
public record TruncatedRide(Instant boardAt, Instant alightAt, Integer rideDurationSeconds,
                             List<StopOccurrence> stops) {

    public TruncatedRide {
        stops = stops == null ? List.of() : List.copyOf(stops);
    }

    /**
     * @param leg                  대중교통 leg. {@code leg.stops()} 가 board 를 포함해야 한다
     * @param alightOccurrenceIndex X 의 {@link StopOccurrence#occurrenceIndex()}
     * @throws IllegalArgumentException leg 에 stop 이 없거나, X 를 못 찾거나, X 가 board 자신일 때
     */
    public static TruncatedRide truncateAt(RoutingLeg leg, int alightOccurrenceIndex) {
        List<StopOccurrence> stops = leg.stops();
        if (stops.isEmpty()) {
            throw new IllegalArgumentException("leg has no stops to truncate: trip=" + leg.tripId());
        }
        int alightIdx = indexOfOccurrence(stops, alightOccurrenceIndex);
        if (alightIdx < 0) {
            throw new IllegalArgumentException(
                    "occurrenceIndex " + alightOccurrenceIndex + " not found in leg trip=" + leg.tripId());
        }
        if (alightIdx == 0) {
            throw new IllegalArgumentException(
                    "occurrenceIndex " + alightOccurrenceIndex + " is the board stop itself, nothing to ride");
        }

        List<StopOccurrence> truncated = stops.subList(0, alightIdx + 1);
        StopOccurrence board = truncated.get(0);
        StopOccurrence alight = truncated.get(truncated.size() - 1);

        Instant boardAt = bestDeparture(board);
        Instant alightAt = bestArrival(alight);
        Integer seconds = (boardAt == null || alightAt == null)
                ? null
                : (int) Duration.between(boardAt, alightAt).getSeconds();

        return new TruncatedRide(boardAt, alightAt, seconds, truncated);
    }

    private static int indexOfOccurrence(List<StopOccurrence> stops, int occurrenceIndex) {
        for (int i = 0; i < stops.size(); i++) {
            if (stops.get(i).occurrenceIndex() == occurrenceIndex) {
                return i;
            }
        }
        return -1;
    }

    /** 실시간이 있으면 실시간, 없으면 시간표(둘 다 없으면 null — 지어내지 않는다). */
    private static Instant bestDeparture(StopOccurrence stop) {
        return stop.realtimeDeparture() != null ? stop.realtimeDeparture() : stop.scheduledDeparture();
    }

    private static Instant bestArrival(StopOccurrence stop) {
        return stop.realtimeArrival() != null ? stop.realtimeArrival() : stop.scheduledArrival();
    }
}
