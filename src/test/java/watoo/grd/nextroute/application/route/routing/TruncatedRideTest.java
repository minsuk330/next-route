package watoo.grd.nextroute.application.route.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** journey-reroute-nextstop PR-A: board→X 절단 연산(순수 뺄셈, 투영 없음). */
class TruncatedRideTest {

    private static final Instant T0 = Instant.parse("2026-09-08T08:12:28Z");

    private StopOccurrence stop(int occurrenceIndex, Instant scheduled) {
        return new StopOccurrence("stop-" + occurrenceIndex, null, null, occurrenceIndex,
                127.0 + occurrenceIndex * 0.01, 37.5, "정류장" + occurrenceIndex,
                scheduled, scheduled, null, null);
    }

    private RoutingLeg busLeg(List<StopOccurrence> stops) {
        return new RoutingLeg(RoutingMode.BUS, null, "1", "1:A1", "1:R", "143",
                "1:TRIP", null, T0, T0.plusSeconds(936), 936, stops, List.of(),
                LegGeometry.missing("no shape"), Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), null);
    }

    @Test
    @DisplayName("board→X 소요 = X 시각 - board 시각, 순수 뺄셈(PR-0 실측: 622s)")
    void computesDurationByPureSubtraction() {
        RoutingLeg leg = busLeg(List.of(
                stop(0, Instant.parse("2026-09-08T08:12:28Z")),
                stop(1, Instant.parse("2026-09-08T08:14:10Z")),
                stop(2, Instant.parse("2026-09-08T08:15:30Z")),
                stop(3, Instant.parse("2026-09-08T08:19:40Z")),
                stop(4, Instant.parse("2026-09-08T08:20:30Z")),
                stop(5, Instant.parse("2026-09-08T08:22:50Z")),
                stop(6, Instant.parse("2026-09-08T08:28:04Z"))));

        TruncatedRide result = TruncatedRide.truncateAt(leg, 5);

        assertThat(result.rideDurationSeconds()).isEqualTo(622);
        assertThat(result.boardAt()).isEqualTo(Instant.parse("2026-09-08T08:12:28Z"));
        assertThat(result.alightAt()).isEqualTo(Instant.parse("2026-09-08T08:22:50Z"));
    }

    @Test
    @DisplayName("지도선 = board..X 정류장 좌표 그대로(디코드·투영 없음), X 이후는 버림")
    void slicesStopsUpToAlightAndDropsRest() {
        RoutingLeg leg = busLeg(List.of(
                stop(0, T0), stop(1, T0.plusSeconds(100)),
                stop(2, T0.plusSeconds(200)), stop(3, T0.plusSeconds(300))));

        TruncatedRide result = TruncatedRide.truncateAt(leg, 2);

        assertThat(result.stops()).extracting(StopOccurrence::occurrenceIndex)
                .containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("실시간 있으면 실시간 우선(시간표 아님)")
    void prefersRealtimeOverScheduled() {
        StopOccurrence board = new StopOccurrence("s0", null, null, 0, 127.0, 37.5, "출발",
                T0, T0, null, T0.plusSeconds(60));
        StopOccurrence alight = new StopOccurrence("s1", null, null, 1, 127.01, 37.5, "도착",
                T0.plusSeconds(300), T0.plusSeconds(300), T0.plusSeconds(330), null);
        RoutingLeg leg = busLeg(List.of(board, alight));

        TruncatedRide result = TruncatedRide.truncateAt(leg, 1);

        // board 는 realtimeDeparture(T0+60), alight 는 realtimeArrival(T0+330) → 270s. 시간표(300s) 아님.
        assertThat(result.boardAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(result.alightAt()).isEqualTo(T0.plusSeconds(330));
        assertThat(result.rideDurationSeconds()).isEqualTo(270);
    }

    @Test
    @DisplayName("시각 없는 정류장이면 소요를 지어내지 않고 null")
    void missingTimingYieldsNullDuration() {
        StopOccurrence board = new StopOccurrence("s0", null, null, 0, 127.0, 37.5, "출발");
        StopOccurrence alight = new StopOccurrence("s1", null, null, 1, 127.01, 37.5, "도착",
                T0.plusSeconds(300), T0.plusSeconds(300), null, null);
        RoutingLeg leg = busLeg(List.of(board, alight));

        TruncatedRide result = TruncatedRide.truncateAt(leg, 1);

        assertThat(result.boardAt()).isNull();
        assertThat(result.rideDurationSeconds()).isNull();
    }

    @Test
    @DisplayName("X 를 leg 안에서 못 찾으면 IllegalArgumentException")
    void unknownAlightIndexThrows() {
        RoutingLeg leg = busLeg(List.of(stop(0, T0), stop(1, T0.plusSeconds(100))));

        assertThatThrownBy(() -> TruncatedRide.truncateAt(leg, 9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("X 가 board 자신이면(탈 게 없음) IllegalArgumentException")
    void alightAtBoardThrows() {
        RoutingLeg leg = busLeg(List.of(stop(0, T0), stop(1, T0.plusSeconds(100))));

        assertThatThrownBy(() -> TruncatedRide.truncateAt(leg, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
