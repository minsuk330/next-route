package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 목적지 ETA 합산의 계약.
 *
 * <p>가장 중요한 시험은 <b>환승이 둘 이상인 여정에서도 값이 나오는지</b>다. 대기 단계 소요는
 * 언제나 비어 있어서, "값이 없으면 포기"로 짜면 그런 여정은 제안이 영원히 0건이 된다(R1).
 */
@DisplayName("재탐색 후보 ETA 합산")
class RouteOptionEtaCalculatorTest {

    private static final Instant BOARDING = Instant.parse("2026-08-11T09:00:00Z");

    private final RouteOptionEtaCalculator calculator = new RouteOptionEtaCalculator();

    @Test
    @DisplayName("승차 이후 구간의 소요를 더한다")
    void sumsDownstreamDurations() {
        List<JourneyLeg> legs = List.of(
                leg(0, JourneyLegType.WALK, 300),
                leg(1, JourneyLegType.WAIT_BUS, null),
                leg(2, JourneyLegType.RIDE_BUS, 900),
                leg(3, JourneyLegType.WALK, 240));

        Optional<RouteOptionEtaCalculator.Result> result =
                calculator.compute(legs, legs.get(1), BOARDING);

        assertThat(result).get().satisfies(r -> {
            assertThat(r.destinationEtaAt()).isEqualTo(BOARDING.plusSeconds(1_140));
            assertThat(r.remainingRideSec()).isEqualTo(1_140);
            assertThat(r.walkingSec())
                    .as("대기 단계 앞의 도보는 이미 지나온 구간이다")
                    .isEqualTo(240);
            assertThat(r.hasFurtherTransfer()).isFalse();
        });
    }

    @Test
    @DisplayName("환승이 둘 이상이어도 값이 나온다 — 이후 대기는 0으로 둔다")
    void multiTransferJourneyStillProducesAnEta() {
        List<JourneyLeg> legs = List.of(
                leg(0, JourneyLegType.WAIT_BUS, null),
                leg(1, JourneyLegType.RIDE_BUS, 600),
                leg(2, JourneyLegType.TRANSFER_WALK, 180),
                leg(3, JourneyLegType.WAIT_BUS, null),
                leg(4, JourneyLegType.RIDE_BUS, 720));

        Optional<RouteOptionEtaCalculator.Result> result =
                calculator.compute(legs, legs.get(0), BOARDING);

        assertThat(result)
                .as("대기 소요는 언제나 비어 있다. 여기서 포기하면 기능이 통째로 죽는다")
                .get()
                .satisfies(r -> {
                    assertThat(r.remainingRideSec()).isEqualTo(1_500);
                    assertThat(r.hasFurtherTransfer())
                            .as("뒤에 대기가 남았으면 절대 시각이 낙관적이라 신뢰도를 낮춰야 한다")
                            .isTrue();
                });
    }

    @Test
    @DisplayName("이후 대기가 없으면 신뢰도를 낮추지 않는다")
    void lastTransferKeepsConfidence() {
        List<JourneyLeg> legs = List.of(
                leg(0, JourneyLegType.WAIT_BUS, null),
                leg(1, JourneyLegType.RIDE_BUS, 600));

        assertThat(calculator.compute(legs, legs.get(0), BOARDING))
                .get()
                .satisfies(r -> assertThat(r.hasFurtherTransfer()).isFalse());
    }

    @Test
    @DisplayName("승차 소요가 비면 후보를 버린다 — 0으로 치면 없는 개선폭이 생긴다")
    void missingRideDurationDropsCandidate() {
        List<JourneyLeg> legs = List.of(
                leg(0, JourneyLegType.WAIT_BUS, null),
                leg(1, JourneyLegType.RIDE_BUS, null));

        assertThat(calculator.compute(legs, legs.get(0), BOARDING)).isEmpty();
    }

    @Test
    @DisplayName("도보 소요가 비어도 후보를 버린다")
    void missingWalkDurationDropsCandidate() {
        List<JourneyLeg> legs = List.of(
                leg(0, JourneyLegType.WAIT_BUS, null),
                leg(1, JourneyLegType.RIDE_BUS, 600),
                leg(2, JourneyLegType.WALK, null));

        assertThat(calculator.compute(legs, legs.get(0), BOARDING)).isEmpty();
    }

    @Test
    @DisplayName("대기 단계 앞 구간은 보지 않는다 — 예측 승차 시각에 이미 들어 있다")
    void upstreamLegsAreIgnored() {
        List<JourneyLeg> legs = List.of(
                leg(0, JourneyLegType.RIDE_BUS, null),
                leg(1, JourneyLegType.TRANSFER_WALK, null),
                leg(2, JourneyLegType.WAIT_BUS, null),
                leg(3, JourneyLegType.RIDE_BUS, 600));

        assertThat(calculator.compute(legs, legs.get(2), BOARDING))
                .as("앞 구간의 깨진 값 때문에 후보를 버리면 안 된다")
                .get()
                .satisfies(r -> assertThat(r.remainingRideSec()).isEqualTo(600));
    }

    @Test
    @DisplayName("override 는 대기 직후 RIDE_BUS 소요만 후보 노선값으로 바꾼다 — 나머지는 그대로")
    void overrideReplacesRideBusDurationOnly() {
        List<JourneyLeg> legs = List.of(
                leg(0, JourneyLegType.WALK, 300),
                leg(1, JourneyLegType.WAIT_BUS, null),
                leg(2, JourneyLegType.RIDE_BUS, 900),
                leg(3, JourneyLegType.WALK, 240));

        // 후보 노선의 시간표 소요 = 600(원래 900 대신)
        Optional<RouteOptionEtaCalculator.Result> result =
                calculator.compute(legs, legs.get(1), BOARDING, 600);

        assertThat(result).get().satisfies(r -> {
            // 승차 600 + 하차 후 도보 240 = 840(원래 1140 에서 승차 조각만 교체)
            assertThat(r.remainingRideSec()).isEqualTo(840);
            assertThat(r.destinationEtaAt()).isEqualTo(BOARDING.plusSeconds(840));
            assertThat(r.walkingSec())
                    .as("RIDE_BUS override 는 도보 합을 건드리지 않는다")
                    .isEqualTo(240);
        });
    }

    @Test
    @DisplayName("override=null 이면 4-arg 도 기존 3-arg 와 완전히 같다(하위호환)")
    void nullOverrideMatchesLegacy() {
        List<JourneyLeg> legs = List.of(
                leg(0, JourneyLegType.WALK, 300),
                leg(1, JourneyLegType.WAIT_BUS, null),
                leg(2, JourneyLegType.RIDE_BUS, 900),
                leg(3, JourneyLegType.WALK, 240));

        assertThat(calculator.compute(legs, legs.get(1), BOARDING, null))
                .isEqualTo(calculator.compute(legs, legs.get(1), BOARDING));
    }

    @Test
    @DisplayName("대기 직후 자리가 RIDE_BUS 가 아니면(도보) 후보를 버린다 — WALK 오대체 방지")
    void discardsWhenSlotAfterWaitIsNotRideBus() {
        List<JourneyLeg> legs = List.of(
                leg(0, JourneyLegType.WAIT_BUS, null),
                leg(1, JourneyLegType.TRANSFER_WALK, 180),  // 대기 직후가 도보
                leg(2, JourneyLegType.RIDE_BUS, 900));

        assertThat(calculator.compute(legs, legs.get(0), BOARDING, 600)).isEmpty();
    }

    @Test
    @DisplayName("override 대상 RIDE_BUS 자리가 아예 없으면 후보를 버린다")
    void discardsWhenNoRideBusSlot() {
        List<JourneyLeg> legs = List.of(
                leg(0, JourneyLegType.WAIT_BUS, null),
                leg(1, JourneyLegType.WAIT_BUS, null));  // legIndex 1 도 대기 → override 못 붙임

        assertThat(calculator.compute(legs, legs.get(0), BOARDING, 600)).isEmpty();
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private JourneyLeg leg(int legIndex, JourneyLegType type, Integer durationSec) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(42L)
                .routeRevision(1)
                .legIndex(legIndex)
                .type(type)
                .state(JourneyLegState.PENDING)
                .plannedDurationSec(durationSec)
                .build();
        ReflectionTestUtils.setField(leg, "id", (long) legIndex + 1);
        return leg;
    }
}
