package watoo.grd.nextroute.application.route.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OTP 기본 순서는 "창 안 출발편의 도착시각 타임라인"이라 배차 잦은 버스가 상위를 채운다(2026-10-12 실측).
 * 정렬기는 같은 대중교통 지문을 하나로 묶고 일반화비용 순으로 줄 세운다.
 */
class ItineraryRankerTest {

    private static final Instant T0 = Instant.parse("2026-10-12T04:30:00Z");

    @Test
    @DisplayName("일반화비용 오름차순으로 줄 세운다")
    void sortsByGeneralizedCost() {
        RoutingItinerary bus = it(40, 3444, transit(RoutingMode.BUS, "1:740", "740"));
        RoutingItinerary subway = it(44, 3557, transit(RoutingMode.SUBWAY, "1:S2", "서울2호선"));
        RoutingItinerary twoTransfers = it(43, 5264,
                transit(RoutingMode.BUS, "1:420", "420"), transit(RoutingMode.BUS, "1:472", "472"));

        List<RoutingItinerary> out = ItineraryRanker.rank(List.of(twoTransfers, subway, bus), 5);

        assertThat(out).containsExactly(bus, subway, twoTransfers);
    }

    @Test
    @DisplayName("비용이 같으면 엔진 순서를 지킨다")
    void tieKeepsEngineOrder() {
        RoutingItinerary a = it(40, 4000, transit(RoutingMode.BUS, "1:A", "A"));
        RoutingItinerary b = it(41, 4000, transit(RoutingMode.BUS, "1:B", "B"));

        assertThat(ItineraryRanker.rank(List.of(a, b), 5)).containsExactly(a, b);
        assertThat(ItineraryRanker.rank(List.of(b, a), 5)).containsExactly(b, a);
    }

    @Test
    @DisplayName("같은 노선 흐름은 가장 이른 도착 하나만 남긴다 — 출발시각만 다른 반복을 지운다")
    void sameFingerprintKeepsEarliestArrival() {
        RoutingItinerary first = it(41, 3444, transit(RoutingMode.BUS, "1:740", "740"));
        RoutingItinerary later = it(54, 3411, transit(RoutingMode.BUS, "1:740", "740"));
        RoutingItinerary subway = it(51, 3557, transit(RoutingMode.SUBWAY, "1:S2", "서울2호선"));

        List<RoutingItinerary> out = ItineraryRanker.rank(List.of(first, subway, later), 2);

        // later 는 비용이 더 낮아도 같은 노선의 다음 편이라 대표가 될 수 없다.
        assertThat(out).containsExactly(first, subway);
    }

    @Test
    @DisplayName("이름이 같아도 노선 id 가 다르면 다른 경로다 — 1호선 일반과 급행")
    void sameNameDifferentRouteIdIsDistinct() {
        RoutingItinerary local = it(53, 5000, transit(RoutingMode.SUBWAY, "1:RR_ACC1_S-1-01-1U", "서울1호선"));
        RoutingItinerary express = it(48, 4800, transit(RoutingMode.SUBWAY, "1:RR_ACC1_S-1-01-4U", "서울1호선"));

        assertThat(ItineraryRanker.rank(List.of(local, express), 1)).containsExactly(express);
        assertThat(ItineraryRanker.rank(List.of(local, express), 5)).containsExactly(express, local);
    }

    @Test
    @DisplayName("고유 경로가 상한보다 적으면 남은 출발편으로 엔진 순서대로 채운다")
    void fillsWithRemainingDeparturesWhenDistinctIsShort() {
        RoutingItinerary d1 = it(53, 5000, transit(RoutingMode.SUBWAY, "1:L1", "서울1호선"));
        RoutingItinerary d2 = it(63, 5000, transit(RoutingMode.SUBWAY, "1:L1", "서울1호선"));
        RoutingItinerary d3 = it(81, 5000, transit(RoutingMode.SUBWAY, "1:L1", "서울1호선"));

        assertThat(ItineraryRanker.rank(List.of(d1, d2, d3), 5)).containsExactly(d1, d2, d3);
        assertThat(ItineraryRanker.rank(List.of(d1, d2, d3), 2)).containsExactly(d1, d2);
    }

    @Test
    @DisplayName("6번째 이후에 온 저비용 후보가 상위로 올라온다")
    void lateCandidateCanRiseIntoTop() {
        List<RoutingItinerary> engine = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            engine.add(it(40 + i, 5000 + i, transit(RoutingMode.BUS, "1:B" + i, "B" + i)));
        }
        RoutingItinerary subway = it(60, 3557, transit(RoutingMode.SUBWAY, "1:S2", "서울2호선"));
        engine.add(subway);

        List<RoutingItinerary> out = ItineraryRanker.rank(engine, 5);

        assertThat(out).hasSize(5);
        assertThat(out.get(0)).isSameAs(subway);
    }

    @Test
    @DisplayName("비용을 모르는 후보는 뒤로, 그 안에선 엔진 순서")
    void nullCostGoesLast() {
        RoutingItinerary unknownA = it(40, null, transit(RoutingMode.BUS, "1:A", "A"));
        RoutingItinerary known = it(50, 6000, transit(RoutingMode.BUS, "1:K", "K"));
        RoutingItinerary unknownB = it(41, null, transit(RoutingMode.BUS, "1:B", "B"));

        assertThat(ItineraryRanker.rank(List.of(unknownA, known, unknownB), 5))
                .containsExactly(known, unknownA, unknownB);
    }

    @Test
    @DisplayName("도보만 경로는 하나의 지문으로 묶인다")
    void walkOnlyIsOneGroup() {
        RoutingItinerary walk1 = it(30, 3000, walk());
        RoutingItinerary walk2 = it(31, 3000, walk());
        RoutingItinerary bus = it(25, 3500, walk(), transit(RoutingMode.BUS, "1:A", "A"), walk());

        assertThat(ItineraryRanker.rank(List.of(walk1, walk2, bus), 2)).containsExactly(walk1, bus);
    }

    @Test
    @DisplayName("도보 구간 차이는 지문에 들어가지 않는다")
    void walkLegsDoNotSplitFingerprint() {
        RoutingItinerary a = it(41, 3444, walk(), transit(RoutingMode.BUS, "1:740", "740"), walk());
        RoutingItinerary b = it(45, 3500, transit(RoutingMode.BUS, "1:740", "740"), walk());

        assertThat(ItineraryRanker.rank(List.of(a, b), 1)).containsExactly(a);
        assertThat(ItineraryRanker.rank(List.of(a, b), 5)).containsExactly(a, b);
    }

    @Test
    @DisplayName("빈 입력·상한 0 은 빈 결과")
    void emptyInputs() {
        RoutingItinerary a = it(41, 3444, transit(RoutingMode.BUS, "1:740", "740"));

        assertThat(ItineraryRanker.rank(List.of(), 5)).isEmpty();
        assertThat(ItineraryRanker.rank(List.of(a), 0)).isEmpty();
    }

    private static RoutingItinerary it(int arrivalMinutes, Integer cost, RoutingLeg... legs) {
        return new RoutingItinerary(T0.plusSeconds(arrivalMinutes * 60L), 0, 0, 0, 0, false,
                List.of(), Fare.unknown(), List.of(legs), cost);
    }

    private static RoutingLeg transit(RoutingMode mode, String routeId, String routeName) {
        return new RoutingLeg(mode, mode.name(), "1", "1:A1", routeId, routeName,
                "1:T", null, T0, T0, 600, List.of(), List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of());
    }

    private static RoutingLeg walk() {
        return new RoutingLeg(RoutingMode.WALK, null, null, null, null, null,
                null, null, T0, T0, 0, List.of(), List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of());
    }
}
