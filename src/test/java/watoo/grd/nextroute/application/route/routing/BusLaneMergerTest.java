package watoo.grd.nextroute.application.route.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 버스 합치기(otp-bus-lane-merge) 거르기 규칙. 예시는 2026-11-16 운영 OTP 실측(서울역 5번승강장 → 명동입구, 대표 8100 탑승 382초).
 */
class BusLaneMergerTest {

    private static final Instant T0 = Instant.parse("2026-11-16T23:40:00Z");
    private static final BusLaneMerger.Rules RULES = new BusLaneMerger.Rules(6, 1.3, 300);
    private static final String FROM = "1:BS_TAGO_SEB102000231";
    private static final String TO = "1:BS_TAGO_GGB101000148";

    @Test
    @DisplayName("nextLegs 원재료에서 예약 노선·대표 자신을 빼고 승차 이른 순으로 싣는다")
    void filtersReservationAndSelf() {
        RoutingLeg leg = bus("8100", 382, alt("5000A", 8, 382), alt("5005(예약)", 9, 382),
                alt("9200", 11, 382), alt("8100", 12, 382), alt("4103", 14, 382));

        List<String> out = names(merge(it(leg)));

        assertThat(out).containsExactly("5000A", "9200", "4103");
    }

    @Test
    @DisplayName("탑승시간이 대표 × 1.3 + 5분을 넘는 우회 노선은 뺀다(수원 2-1)")
    void dropsMuchLongerRides() {
        // 상한 = 382 × 1.3 + 300 = 797초
        RoutingLeg leg = bus("8100", 382, alt("3201", 5, 797), alt("2-1", 6, 798));

        assertThat(names(merge(it(leg)))).containsExactly("3201");
    }

    @Test
    @DisplayName("하차 정류장이 다른 편은 뺀다")
    void dropsDifferentAlightStop() {
        AlternativeRoute other = new AlternativeRoute("1:X", "X", null, "1:ELSEWHERE",
                T0.plusSeconds(60), T0.plusSeconds(442));
        RoutingLeg leg = bus("8100", 382, other, alt("1101", 7, 382));

        assertThat(names(merge(it(leg)))).containsExactly("1101");
    }

    @Test
    @DisplayName("같은 이름은 가장 이른 편 하나만")
    void dedupesByName() {
        RoutingLeg leg = bus("8100", 382, alt("9200", 20, 382), alt("9200", 3, 382));

        List<AlternativeRoute> out = merge(it(leg)).legs().get(0).alternatives();

        assertThat(out).hasSize(1);
        assertThat(out.get(0).boardAt()).isEqualTo(T0.plusSeconds(180));
    }

    @Test
    @DisplayName("자주 오는 노선이 먼저다 — 편 수가 같으면 승차 이른 순(사당역(중)→과천: 11-5·11-2 8편, 1-1 5편, 7007-1 2편)")
    void ordersByFrequencyThenEarliest() {
        List<AlternativeRoute> alts = new ArrayList<>();
        alts.add(alt("7007-1", 2, 382));
        alts.add(alt("7007-1", 40, 382));
        for (int i = 0; i < 5; i++) alts.add(alt("1-1", 7 + i * 10, 382));
        for (int i = 0; i < 8; i++) alts.add(alt("11-2", 11 + i * 6, 382));
        for (int i = 0; i < 8; i++) alts.add(alt("11-5", 1 + i * 6, 382));
        RoutingLeg leg = bus("777", 382, alts.toArray(AlternativeRoute[]::new));

        assertThat(names(merge(it(leg)))).containsExactly("11-5", "11-2", "1-1", "7007-1");
    }

    @Test
    @DisplayName("대표 포함 lane 상한 6 → 대체 노선 5개까지")
    void capsLanes() {
        List<AlternativeRoute> alts = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            alts.add(alt("R" + i, i + 1, 382));
        }
        RoutingLeg leg = bus("8100", 382, alts.toArray(AlternativeRoute[]::new));

        assertThat(names(merge(it(leg)))).containsExactly("R0", "R1", "R2", "R3", "R4");
    }

    @Test
    @DisplayName("A: 같은 지문으로 묶인 다른 후보의 버스 노선도 싣는다(M4130 대표 + M4137)")
    void addsRoutesFromGroupedCandidates() {
        RoutingItinerary rep = it(bus("M4130", 300));
        RoutingItinerary other = it(bus("M4137", 320).withAlternatives(List.of()));
        RoutingItinerary subway = it(new RoutingLeg(RoutingMode.SUBWAY, "SUBWAY", "1", null, "1:S2", "서울2호선",
                null, null, T0, T0.plusSeconds(600), 600, List.of(), List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of()));

        List<RoutingItinerary> out = BusLaneMerger.merge(List.of(rep, subway), List.of(rep, other, subway), RULES);

        assertThat(names(out.get(0))).containsExactly("M4137");
        assertThat(out.get(1).legs().get(0).alternatives()).isEmpty();
    }

    @Test
    @DisplayName("버스가 아닌 구간·정류장 모르는 버스 구간은 원재료를 비운다")
    void clearsNonBusAndUnknownStops() {
        RoutingLeg noStops = new RoutingLeg(RoutingMode.BUS, "BUS", "1", null, "1:740", "740", null, null,
                T0, T0.plusSeconds(600), 600, List.of(), List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of())
                .withAlternatives(List.of(alt("741", 1, 600)));

        assertThat(merge(it(noStops)).legs().get(0).alternatives()).isEmpty();
    }

    @Test
    @DisplayName("clear 는 모든 대체 노선을 비운다(버스 합치기 끔)")
    void clearRemovesAll() {
        RoutingItinerary it = it(bus("8100", 382, alt("9200", 1, 382)));

        assertThat(BusLaneMerger.clear(List.of(it)).get(0).legs().get(0).alternatives()).isEmpty();
    }

    private static RoutingItinerary merge(RoutingItinerary it) {
        return BusLaneMerger.merge(List.of(it), List.of(it), RULES).get(0);
    }

    private static List<String> names(RoutingItinerary it) {
        return it.legs().get(0).alternatives().stream().map(AlternativeRoute::label).toList();
    }

    private static RoutingItinerary it(RoutingLeg leg) {
        return new RoutingItinerary(leg.alightAt(), 0, 0, 0, 0, false, List.of(), Fare.unknown(), List.of(leg), 4000);
    }

    private static RoutingLeg bus(String name, int rideSeconds, AlternativeRoute... alternatives) {
        return new RoutingLeg(RoutingMode.BUS, "BUS", "1", null, "1:" + name, name, null, null,
                T0, T0.plusSeconds(rideSeconds), rideSeconds,
                List.of(new StopOccurrence(FROM, null, null, 0, 0, 0, "서울역버스환승센터(5번승강장)(중)"),
                        new StopOccurrence(TO, null, null, 1, 0, 0, "명동입구")),
                List.of(), LegGeometry.missing("x"), Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of())
                .withAlternatives(List.of(alternatives));
    }

    /** 대표 승차 뒤 boardMinutes 분에 타서 rideSeconds 초 걸리는 편. */
    private static AlternativeRoute alt(String name, int boardMinutes, int rideSeconds) {
        Instant board = T0.plusSeconds(boardMinutes * 60L);
        return new AlternativeRoute("1:" + name, name, null, TO, board, board.plusSeconds(rideSeconds));
    }
}
