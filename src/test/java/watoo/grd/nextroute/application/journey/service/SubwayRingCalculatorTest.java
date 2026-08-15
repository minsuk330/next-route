package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.journey.dto.SubwayRingContext;
import watoo.grd.nextroute.application.journey.dto.SubwayRingObservation;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPhase;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalCode;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 링 계산의 계약. <b>여기서 막지 못하면 사용자가 역을 지나친다.</b>
 *
 * <p>가장 조용한 사고 둘을 먼저 고정한다 — {@code arvlCd} 를 초보다 먼저 보는 것(안 그러면 3
 * 정거장 전 열차가 90% 로 보인다)과, 남은 초를 못 얻었을 때 <b>정적 값으로 채우지 않는 것</b>.
 */
@DisplayName("지하철 진행 링 계산")
class SubwayRingCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-08-15T09:00:00Z");
    private static final Long JOURNEY_ID = 77L;

    private final JourneySubwayRingProperties properties = new JourneySubwayRingProperties();
    private final SubwayRingCalculator calculator = new SubwayRingCalculator(properties);

    /** 승차역(0) → 역A(1) → 역B(2) → 하차역(3). */
    private final SubwayRingContext context = new SubwayRingContext(JOURNEY_ID, 1, 4, 0,
            new SubwayRingChain(List.of(
                    new SubwayRingChain.Station(0, "1002000200", "승차역"),
                    new SubwayRingChain.Station(1, "1002000201", "역A"),
                    new SubwayRingChain.Station(2, "1002000202", "역B"),
                    new SubwayRingChain.Station(3, "1002000203", "하차역"))));

    // ── 코드가 초보다 먼저다 ────────────────────────────────────────────────

    @Nested
    @DisplayName("arvlCd 를 남은 초보다 먼저 본다")
    class CodeBeforeSeconds {

        @Test
        @DisplayName("arvlCd=99 + 초 없음 은 '아직 멀다' — freeze 하고 STALE 이다")
        void enrouteWithoutSecondsFreezes() {
            SubwayRingState previous = enroute(BigDecimal.valueOf(0.30), T0, T0.plusSeconds(100));

            SubwayRingState state = calculator.next(previous, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1, null,
                            T0.plusSeconds(30)),
                    T0.plusSeconds(30));

            assertThat(state.segmentProgress()).isEqualByComparingTo("0.30");
            assertThat(state.quality()).isEqualTo(SubwayRingQuality.STALE);
            assertThat(state.estimatedNextStationAt()).isNull();
        }

        @Test
        @DisplayName("arvlCd=1 + 초 없음 은 '다 왔다' — 1.00 이다. 같은 0 이 반대로 처리된다")
        void arrivedWithoutSecondsIsComplete() {
            SubwayRingState state = calculator.next(
                    enroute(BigDecimal.valueOf(0.30), T0, T0.plusSeconds(100)), context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ARRIVED, 1, null,
                            T0.plusSeconds(30)),
                    T0.plusSeconds(30));

            assertThat(state.segmentProgress()).isEqualByComparingTo("1.00");
            assertThat(state.estimatedNextStationAt()).isNull();
        }
    }

    // ── 보간과 역행 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("남은 초가 줄면 진행이 오른다")
    void progressRisesAsArrivalNears() {
        SubwayRingState first = calculator.next(null, context,
                SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 1,
                        T0.plusSeconds(120), T0),
                T0);
        assertThat(first.segmentProgress()).isEqualByComparingTo("0");

        SubwayRingState second = calculator.next(first, context,
                SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1,
                        T0.plusSeconds(120), T0.plusSeconds(60)),
                T0.plusSeconds(60));

        // 120초 구간의 절반을 지났다 → 상한 0.90 의 절반.
        assertThat(second.segmentProgress()).isEqualByComparingTo("0.45");
        assertThat(second.progressBaseAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    @DisplayName("도착 예정이 늘어나도 서버 앵커는 역행하지 않는다")
    void anchorNeverGoesBackward() {
        SubwayRingState previous = enroute(BigDecimal.valueOf(0.60), T0, T0.plusSeconds(60));

        SubwayRingState state = calculator.next(previous, context,
                SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1,
                        T0.plusSeconds(300), T0.plusSeconds(10)),
                T0.plusSeconds(10));

        assertThat(state.segmentProgress()).isGreaterThanOrEqualTo(BigDecimal.valueOf(0.60));
        assertThat(state.estimatedNextStationAt()).isEqualTo(T0.plusSeconds(300));
    }

    @Test
    @DisplayName("이동 중에는 상한 0.90 을 넘지 않는다")
    void movingProgressIsCapped() {
        SubwayRingState previous = enroute(BigDecimal.valueOf(0.80), T0, T0.plusSeconds(60));

        SubwayRingState state = calculator.next(previous, context,
                SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1,
                        T0.plusSeconds(200), T0.plusSeconds(59)),
                T0.plusSeconds(59));

        assertThat(state.segmentProgress()).isLessThanOrEqualTo(BigDecimal.valueOf(0.90));
    }

    @Nested
    @DisplayName("분모 경계에서 예외도 NaN 도 없다")
    class Boundaries {

        @Test
        @DisplayName("직전 예정 시각이 base 보다 이르면(분모 0·음수) 상한으로 떨어진다")
        void nonPositiveDenominatorFallsToTarget() {
            SubwayRingState previous = enroute(BigDecimal.valueOf(0.10),
                    T0.plusSeconds(100), T0.plusSeconds(100));

            SubwayRingState state = calculator.next(previous, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1,
                            T0.plusSeconds(220), T0.plusSeconds(120)),
                    T0.plusSeconds(120));

            assertThat(state.segmentProgress()).isEqualByComparingTo("0.90");
        }

        @Test
        @DisplayName("예정 시각을 이미 지났으면 상한으로 떨어진다")
        void pastEstimateFallsToTarget() {
            SubwayRingState previous = enroute(BigDecimal.valueOf(0.10), T0, T0.plusSeconds(60));

            SubwayRingState state = calculator.next(previous, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1,
                            T0.plusSeconds(180), T0.plusSeconds(90)),
                    T0.plusSeconds(90));

            assertThat(state.segmentProgress()).isEqualByComparingTo("0.90");
        }
    }

    // ── 역 이벤트 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("역 진입은 0.95 다 — 도착 이벤트 전에는 1.00 이 없다")
    void enteringIsNotArrival() {
        SubwayRingState state = calculator.next(
                enroute(BigDecimal.valueOf(0.80), T0, T0.plusSeconds(60)), context,
                SubwayRingObservation.arrival(SubwayArrivalCode.ENTERING, 1, null,
                        T0.plusSeconds(50)),
                T0.plusSeconds(50));

        assertThat(state.segmentProgress()).isEqualByComparingTo("0.95");
        assertThat(state.nextStationName()).isEqualTo("역A");
    }

    @Test
    @DisplayName("도착한 다음 tick 에 구간이 넘어가고 진행률이 0 으로 초기화된다")
    void arrivalAdvancesSegmentOnNextTick() {
        SubwayRingState arrived = calculator.next(null, context,
                SubwayRingObservation.arrival(SubwayArrivalCode.ARRIVED, 1, null, T0), T0);
        assertThat(arrived.nextStationName()).isEqualTo("역A");
        assertThat(arrived.segmentProgress()).isEqualByComparingTo("1.00");

        SubwayRingState next = calculator.next(arrived, context,
                SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 2,
                        T0.plusSeconds(160), T0.plusSeconds(40)),
                T0.plusSeconds(40));

        assertThat(next.nextStationSequence()).isEqualTo(2);
        assertThat(next.nextStationName()).isEqualTo("역B");
        assertThat(next.segmentProgress()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("조회역에서 이미 출발했다면 구간을 넘긴다")
    void departedAdvancesSegment() {
        SubwayRingState state = calculator.next(
                enroute(BigDecimal.valueOf(0.50), T0, T0.plusSeconds(60)), context,
                SubwayRingObservation.arrival(SubwayArrivalCode.DEPARTED, 1, null,
                        T0.plusSeconds(30)),
                T0.plusSeconds(30));

        assertThat(state.nextStationSequence()).isEqualTo(2);
        assertThat(state.segmentProgress()).isEqualByComparingTo("0");
    }

    // ── 위치 폴백 ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("도착 목록에 우리 열차가 없어도 구간은 잃지 않는다")
    class PositionFallback {

        @Test
        @DisplayName("위치가 다음 역 출발을 말하면 다음 구간 0 으로 진행한다 — 링은 STALE 이다")
        void positionKeepsSegmentsMoving() {
            SubwayRingState previous = enroute(BigDecimal.valueOf(0.40), T0, T0.plusSeconds(60));

            SubwayRingState state = calculator.next(previous, context,
                    SubwayRingObservation.position(SubwayTrainStatus.DEPARTED, 1,
                            T0.plusSeconds(70)),
                    T0.plusSeconds(70));

            assertThat(state.nextStationSequence()).isEqualTo(2);
            assertThat(state.segmentProgress()).isEqualByComparingTo("0");
            assertThat(state.quality()).isEqualTo(SubwayRingQuality.STALE);
            assertThat(state.estimatedNextStationAt()).isNull();
        }

        @Test
        @DisplayName("역을 몇 개 놓쳤어도 관측한 역까지 건너뛴다")
        void positionSkipsMissedStations() {
            SubwayRingState previous = enroute(BigDecimal.valueOf(0.40), T0, T0.plusSeconds(60));

            SubwayRingState state = calculator.next(previous, context,
                    SubwayRingObservation.position(SubwayTrainStatus.ARRIVED, 2,
                            T0.plusSeconds(70)),
                    T0.plusSeconds(70));

            assertThat(state.nextStationSequence()).isEqualTo(2);
            assertThat(state.segmentProgress()).isEqualByComparingTo("1.00");
        }

        @Test
        @DisplayName("아직 다음 역에 못 왔다면 앵커에 멈춘다")
        void positionBeforeNextStationFreezes() {
            SubwayRingState previous = new SubwayRingState(JOURNEY_ID, 1, 4, 0,
                    2, "1002000202", "역B", "하차역", 2,
                    BigDecimal.valueOf(0.40), T0, BigDecimal.valueOf(0.90),
                    T0.plusSeconds(60), T0.plusSeconds(120),
                    SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, T0, T0);

            SubwayRingState state = calculator.next(previous, context,
                    SubwayRingObservation.position(SubwayTrainStatus.ARRIVED, 1,
                            T0.plusSeconds(30)),
                    T0.plusSeconds(30));

            assertThat(state.nextStationSequence()).isEqualTo(2);
            assertThat(state.segmentProgress()).isEqualByComparingTo("0.40");
            assertThat(state.quality()).isEqualTo(SubwayRingQuality.STALE);
        }
    }

    // ── 모르면 멈춘다 ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("실시간을 잃으면 멈춘다 — 추정으로 채우지 않는다")
    class Freeze {

        @Test
        @DisplayName("관측이 없으면 마지막 값에 멈추고 보간을 끊는다")
        void noObservationFreezes() {
            SubwayRingState previous = enroute(BigDecimal.valueOf(0.55), T0, T0.plusSeconds(60));

            SubwayRingState state = calculator.next(previous, context, null, T0.plusSeconds(45));

            assertThat(state.segmentProgress()).isEqualByComparingTo("0.55");
            assertThat(state.estimatedNextStationAt()).isNull();
            assertThat(state.quality()).isEqualTo(SubwayRingQuality.STALE);
        }

        @Test
        @DisplayName("관측이 freshness 를 넘기면 멈춘다")
        void staleObservationFreezes() {
            SubwayRingState previous = enroute(BigDecimal.valueOf(0.55), T0, T0.plusSeconds(60));
            Instant late = T0.plus(Duration.ofSeconds(10));

            SubwayRingState state = calculator.next(previous, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1,
                            late.plusSeconds(60), late),
                    late.plus(properties.getFreshnessWindow()).plusSeconds(1));

            assertThat(state.segmentProgress()).isEqualByComparingTo("0.55");
            assertThat(state.quality()).isEqualTo(SubwayRingQuality.STALE);
        }

        @Test
        @DisplayName("멈춘 동안 진행률이 정적 소요시간으로 늘어나지 않는다")
        void frozenProgressNeverGrows() {
            SubwayRingState state = enroute(BigDecimal.valueOf(0.55), T0, T0.plusSeconds(60));
            for (int tick = 1; tick <= 10; tick++) {
                state = calculator.next(state, context, null, T0.plusSeconds(tick * 30L));
            }
            assertThat(state.segmentProgress()).isEqualByComparingTo("0.55");
        }
    }

    // ── 국면과 남은 역 수 ───────────────────────────────────────────────────

    @Test
    @DisplayName("다음 역이 하차역일 때만 SUBWAY_ALIGHT 로 바뀐다")
    void alightPhaseOnlyAtAlightingStation() {
        SubwayRingState riding = calculator.next(null, context,
                SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 1,
                        T0.plusSeconds(90), T0),
                T0);
        assertThat(riding.phase()).isEqualTo(SubwayRingPhase.RIDE_SUBWAY);

        SubwayRingState alighting = calculator.next(null, context,
                SubwayRingObservation.position(SubwayTrainStatus.DEPARTED, 2, T0), T0);
        assertThat(alighting.nextStationSequence()).isEqualTo(3);
        assertThat(alighting.phase()).isEqualTo(SubwayRingPhase.SUBWAY_ALIGHT);
    }

    @Test
    @DisplayName("남은 역 수는 하차역을 포함한다 — 다음 역이 하차역이면 1")
    void remainingStationsIncludeAlighting() {
        SubwayRingState first = calculator.next(null, context,
                SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 1,
                        T0.plusSeconds(90), T0),
                T0);
        assertThat(first.remainingStations()).isEqualTo(3);

        SubwayRingState last = calculator.next(null, context,
                SubwayRingObservation.position(SubwayTrainStatus.DEPARTED, 2, T0), T0);
        assertThat(last.remainingStations()).isEqualTo(1);
    }

    @Test
    @DisplayName("하차역 도착 뒤에는 더 넘어가지 않는다")
    void terminalStateHolds() {
        SubwayRingState atAlighting = new SubwayRingState(JOURNEY_ID, 1, 4, 0,
                3, "1002000203", "하차역", "하차역", 1,
                BigDecimal.ONE, T0, BigDecimal.ONE, null, T0.plusSeconds(120),
                SubwayRingPhase.SUBWAY_ALIGHT, SubwayRingQuality.NORMAL, T0, T0);

        SubwayRingState state = calculator.next(atAlighting, context,
                SubwayRingObservation.position(SubwayTrainStatus.DEPARTED, 3, T0.plusSeconds(30)),
                T0.plusSeconds(30));

        assertThat(state.nextStationSequence()).isEqualTo(3);
        assertThat(state.segmentProgress()).isEqualByComparingTo("1");
    }

    // ── 세대 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("다른 고정 세대의 상태는 이어받지 않는다")
    void foreignGenerationIsIgnored() {
        SubwayRingState older = new SubwayRingState(JOURNEY_ID, 1, 4, /* bindingVersion */ 3,
                2, "1002000202", "역B", "하차역", 2,
                BigDecimal.valueOf(0.70), T0, BigDecimal.valueOf(0.90),
                T0.plusSeconds(60), T0.plusSeconds(120),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, T0, T0);

        SubwayRingState state = calculator.next(older, context,
                SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 1,
                        T0.plusSeconds(120), T0),
                T0);

        assertThat(state.bindingVersion()).isZero();
        assertThat(state.nextStationSequence()).isEqualTo(1);
        assertThat(state.segmentProgress()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("체인이 짧으면 안내를 만들지 않는다")
    void unusableChainYieldsNothing() {
        SubwayRingContext broken = new SubwayRingContext(JOURNEY_ID, 1, 4, 0,
                new SubwayRingChain(List.of(
                        new SubwayRingChain.Station(0, "1002000200", "승차역"))));

        assertThat(calculator.next(null, broken,
                SubwayRingObservation.arrival(SubwayArrivalCode.ARRIVED, 1, null, T0), T0))
                .isNull();
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    /** 역A(순번 1) 로 가는 중인 상태. */
    private SubwayRingState enroute(BigDecimal progress, Instant baseAt, Instant estimatedAt) {
        return new SubwayRingState(JOURNEY_ID, 1, 4, 0,
                1, "1002000201", "역A", "하차역", 3,
                progress, baseAt, BigDecimal.valueOf(0.90), estimatedAt,
                baseAt.plus(properties.getFreshnessWindow()),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, baseAt, baseAt);
    }
}
