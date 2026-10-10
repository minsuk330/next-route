package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.journey.dto.SubwayRingContext;
import watoo.grd.nextroute.application.journey.dto.SubwayRingHoldReason;
import watoo.grd.nextroute.application.journey.dto.SubwayRingObservation;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPhase;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingEtaSource;
import watoo.grd.nextroute.application.journey.dto.SubwayRingStage;
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
        @DisplayName("arvlCd=99 + 초 없음 은 '아직 멀다' — 새 근거가 없으니 그리던 직선을 그대로 둔다(STALE 아님)")
        void enrouteWithoutSecondsKeepsTheLine() {
            SubwayRingState previous = enroute(BigDecimal.valueOf(0.30), T0, T0.plusSeconds(100));

            SubwayRingState state = calculator.next(previous, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1, null,
                            T0.plusSeconds(30)),
                    T0.plusSeconds(30));

            // 100초 구간의 30% → 0.30 + (0.90 - 0.30) × 0.3. 같은 직선이 계속된다.
            assertThat(state.shownProgressAt(T0.plusSeconds(30))).isEqualByComparingTo("0.48");
            assertThat(state.estimatedNextStationAt()).isEqualTo(T0.plusSeconds(100));
            assertThat(state.quality()).isEqualTo(SubwayRingQuality.NORMAL);
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
        @DisplayName("아직 다음 역에 못 왔다면 그리던 직선을 그대로 둔다")
        void positionBeforeNextStationKeepsTheLine() {
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
            // 화면 값 = 0.40 + 0.50 × 30/60 — 직선이 이어진다.
            assertThat(state.shownProgressAt(T0.plusSeconds(30))).isEqualByComparingTo("0.65");
            assertThat(state.estimatedNextStationAt()).isEqualTo(T0.plusSeconds(60));
            assertThat(state.quality()).isEqualTo(SubwayRingQuality.NORMAL);
        }
    }

    // ── 모르면 멈춘다 ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("실시간을 잃으면 멈춘다 — 추정으로 채우지 않는다")
    class Freeze {

        @Test
        @DisplayName("관측이 없으면 지금 화면 값에 멈추고 보간을 끊는다")
        void noObservationFreezes() {
            SubwayRingState previous = enroute(BigDecimal.valueOf(0.55), T0, T0.plusSeconds(60));

            SubwayRingState state = calculator.next(previous, context, null, T0.plusSeconds(45));

            // 0.55 + (0.90 - 0.55) × 45/60 — 저장된 base(0.55)로 돌아가면 push 프레임이 링을 되감는다(#357).
            assertThat(state.segmentProgress()).isEqualByComparingTo("0.8125");
            assertThat(state.progressBaseAt()).isEqualTo(T0.plusSeconds(45));
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

            // 예정(T0+60)이 지났으니 앱 화면은 상한에 있다 — 거기서 멈춘다.
            assertThat(state.segmentProgress()).isEqualByComparingTo("0.90");
            assertThat(state.quality()).isEqualTo(SubwayRingQuality.STALE);
        }

        @Test
        @DisplayName("멈춘 동안 진행률이 정적 소요시간으로 늘어나지 않는다")
        void frozenProgressNeverGrows() {
            SubwayRingState state = enroute(BigDecimal.valueOf(0.55), T0, T0.plusSeconds(60));
            state = calculator.next(state, context, null, T0.plusSeconds(30));
            BigDecimal frozen = state.segmentProgress();
            for (int tick = 2; tick <= 10; tick++) {
                state = calculator.next(state, context, null, T0.plusSeconds(tick * 30L));
            }
            // 첫 freeze 는 그때 화면 값(0.725)이고, 그 뒤로는 그대로다.
            assertThat(frozen).isEqualByComparingTo("0.725");
            assertThat(state.segmentProgress()).isEqualByComparingTo(frozen);
        }
    }

    @Nested
    @DisplayName("전역 코드(3/4/5)가 같은 구간에서 반복돼도 0 으로 되감지 않는다")
    class RepeatedPreviousStation {

        @Test
        @DisplayName("전역 출발이 30초 뒤 다시 오면 그동안의 진행을 이어 그린다(journey 158 회귀)")
        void continuesInsteadOfResetting() {
            SubwayRingState first = calculator.next(null, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 1,
                            T0.plusSeconds(120), T0), T0);
            SubwayRingState again = calculator.next(first, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 1,
                            T0.plusSeconds(130), T0.plusSeconds(30)), T0.plusSeconds(30));

            // 120초 구간의 1/4 을 지났다 → 0.90 의 1/4. 새 예정 시각(130초)부터 다시 보간한다.
            assertThat(again.segmentProgress()).isEqualByComparingTo("0.225");
            assertThat(again.progressBaseAt()).isEqualTo(T0.plusSeconds(30));
            assertThat(again.estimatedNextStationAt()).isEqualTo(T0.plusSeconds(130));
            assertThat(again.quality()).isEqualTo(SubwayRingQuality.NORMAL);
        }

        @Test
        @DisplayName("전역 진입·도착(4/5)은 아직 출발 전 — 진행을 그 자리에 세운다(예정 없음, 상한=지금 값, NORMAL)")
        void dwellHoldsInPlace() {
            SubwayRingState previous = enroute(BigDecimal.valueOf(0.40), T0, T0.plusSeconds(100));

            for (SubwayArrivalCode code : new SubwayArrivalCode[]{SubwayArrivalCode.PREV_ARRIVED,
                    SubwayArrivalCode.PREV_ENTERING}) {
                SubwayRingState held = calculator.next(previous, context,
                        SubwayRingObservation.arrival(code, 1, T0.plusSeconds(160), T0.plusSeconds(20)),
                        T0.plusSeconds(20));

                // 100초 구간의 20% 를 그리던 중이다 → 0.40 + 0.50 × 0.2. 거기 선다.
                assertThat(held.segmentProgress()).isEqualByComparingTo("0.50");
                assertThat(held.progressTarget()).isEqualByComparingTo("0.50");
                assertThat(held.estimatedNextStationAt()).isNull();
                assertThat(held.quality()).isEqualTo(SubwayRingQuality.NORMAL);
            }
        }

        @Test
        @DisplayName("반복 관측에 남은 초가 없고 전형 시간도 모르면 그리던 직선을 그대로 둔다 — 0 으로 떨어뜨리지 않는다")
        void keepsTheLineWithoutSeconds() {
            SubwayRingState previous = enroute(BigDecimal.valueOf(0.40), T0, T0.plusSeconds(100));

            SubwayRingState state = calculator.next(previous, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 1, null,
                            T0.plusSeconds(30)), T0.plusSeconds(30));

            assertThat(state.shownProgressAt(T0.plusSeconds(30))).isEqualByComparingTo("0.55");
            assertThat(state.estimatedNextStationAt()).isEqualTo(T0.plusSeconds(100));
            assertThat(state.quality()).isEqualTo(SubwayRingQuality.NORMAL);
        }

        @Test
        @DisplayName("새 구간의 첫 전역 관측은 여전히 0 에서 시작한다")
        void firstObservationOfSegmentStartsAtZero() {
            SubwayRingState arrivedAtA = calculator.next(
                    enroute(BigDecimal.valueOf(0.80), T0, T0.plusSeconds(60)), context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ARRIVED, 1, null,
                            T0.plusSeconds(60)), T0.plusSeconds(60));

            SubwayRingState towardB = calculator.next(arrivedAtA, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 2,
                            T0.plusSeconds(200), T0.plusSeconds(80)), T0.plusSeconds(80));

            assertThat(towardB.nextStationSequence()).isEqualTo(2);
            assertThat(towardB.segmentProgress()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("전역 출발 구간은 앞 열차 실측 예정으로 그린다(2026-10-07 결정)")
    class MeasuredSegment {

        private final Instant start = T0;
        private final Instant eta = T0.plusSeconds(60);

        private SubwayRingObservation measured(Instant observedAt) {
            return SubwayRingObservation.measured(1, start, eta, observedAt);
        }

        @Test
        @DisplayName("첫 관측: 출발 시각에서 0, 예정까지 직선, 유효기간 = 예정 + 신선도 창")
        void firstAnchorStartsAtDeparture() {
            SubwayRingState state = calculator.next(null, context, measured(T0.plusSeconds(20)),
                    T0.plusSeconds(25));

            assertThat(state.nextStationSequence()).isEqualTo(1);
            assertThat(state.segmentProgress()).isEqualByComparingTo("0");
            assertThat(state.progressBaseAt()).isEqualTo(start);
            assertThat(state.estimatedNextStationAt()).isEqualTo(eta);
            assertThat(state.progressTarget()).isEqualByComparingTo("0.90");
            assertThat(state.quality()).isEqualTo(SubwayRingQuality.NORMAL);
            assertThat(state.progressValidUntil()).isEqualTo(eta.plus(properties.getFreshnessWindow()));
        }

        @Test
        @DisplayName("같은 예정이 반복되면 앵커를 바꾸지 않는다 — 관측·갱신 시각만 새로")
        void sameEtaKeepsAnchor() {
            SubwayRingState first = calculator.next(null, context, measured(T0.plusSeconds(20)),
                    T0.plusSeconds(25));
            SubwayRingState again = calculator.next(first, context, measured(T0.plusSeconds(36)),
                    T0.plusSeconds(40));

            assertThat(again.segmentProgress()).isEqualByComparingTo(first.segmentProgress());
            assertThat(again.progressBaseAt()).isEqualTo(first.progressBaseAt());
            assertThat(again.estimatedNextStationAt()).isEqualTo(first.estimatedNextStationAt());
            assertThat(again.progressValidUntil()).isEqualTo(first.progressValidUntil());
            assertThat(again.observedAt()).isEqualTo(T0.plusSeconds(36));
            assertThat(again.updatedAt()).isEqualTo(T0.plusSeconds(40));
        }

        @Test
        @DisplayName("정차(5) 뒤 출발(3)이 보이면 0 에서 출발 시각 기준으로 그리기 시작한다")
        void dwellThenDeparture() {
            SubwayRingState dwell = calculator.next(null, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_ARRIVED, 1, null, T0.minusSeconds(20)),
                    T0.minusSeconds(15));
            SubwayRingState moving = calculator.next(dwell, context, measured(T0.plusSeconds(10)),
                    T0.plusSeconds(12));

            assertThat(dwell.segmentProgress()).isEqualByComparingTo("0");
            assertThat(moving.progressBaseAt()).isEqualTo(start);
            assertThat(moving.estimatedNextStationAt()).isEqualTo(eta);
        }

        @Test
        @DisplayName("예정이 지났는데 도착이 안 보이면 상한(0.90)에서 멈춘다")
        void overrunHoldsAtCap() {
            SubwayRingState first = calculator.next(null, context, measured(T0.plusSeconds(20)),
                    T0.plusSeconds(25));
            SubwayRingState late = calculator.next(first, context, measured(T0.plusSeconds(70)),
                    T0.plusSeconds(75));

            assertThat(late.segmentProgress()).isEqualByComparingTo("0.90");
            assertThat(late.progressTarget()).isEqualByComparingTo("0.90");
            assertThat(late.estimatedNextStationAt()).isNull();
            assertThat(late.quality()).isEqualTo(SubwayRingQuality.NORMAL);
        }

        @Test
        @DisplayName("실측 표본이 없으면 API 초를 쓰지 않고 0 에서 STALE")
        void noSampleIsStale() {
            SubwayRingState state = calculator.next(null, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 1, null, T0), T0);

            assertThat(state.segmentProgress()).isEqualByComparingTo("0");
            assertThat(state.estimatedNextStationAt()).isNull();
            assertThat(state.quality()).isEqualTo(SubwayRingQuality.STALE);
        }

        @Test
        @DisplayName("이미 다른 직선 위에서 움직이던 링은 지금 화면 값에서 실측 예정으로 이어 그린다")
        void movingRingContinues() {
            SubwayRingState previous = enroute(BigDecimal.valueOf(0.30), T0.minusSeconds(30), T0.plusSeconds(90));

            SubwayRingState state = calculator.next(previous, context, measured(T0.plusSeconds(10)),
                    T0.plusSeconds(12));

            assertThat(state.segmentProgress()).isGreaterThanOrEqualTo(BigDecimal.valueOf(0.30));
            assertThat(state.estimatedNextStationAt()).isEqualTo(eta);
            assertThat(state.progressValidUntil()).isEqualTo(eta.plus(properties.getFreshnessWindow()));
        }
    }

    @Test
    @DisplayName("이미 구간을 지난 열차(#311) — 승차역에서 시작했어도 하차역 도착으로 마무리, NORMAL, 낡은 관측이어도")
    void passedTrainFinishesAtAlighting() {
        SubwayRingContext selected = new SubwayRingContext(JOURNEY_ID, 1, 4, 0, context.chain(), 0);

        SubwayRingState state = calculator.next(null, selected,
                SubwayRingObservation.passed(3, T0.minusSeconds(300)), T0);
        SubwayRingState after = calculator.next(state, selected, null, T0.plusSeconds(60));

        assertThat(state.nextStationSequence()).isEqualTo(3);
        assertThat(state.nextStationName()).isEqualTo("하차역");
        assertThat(state.segmentProgress()).isEqualByComparingTo("1.00");
        assertThat(state.phase()).isEqualTo(SubwayRingPhase.SUBWAY_ALIGHT);
        assertThat(state.quality()).isEqualTo(SubwayRingQuality.NORMAL);
        assertThat(after).isEqualTo(state);
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

    // ── 역에서 고른 열차: 승차역부터 (#304) ─────────────────────────────────

    @Nested
    @DisplayName("역에서 고르고 아직 안 움직인 열차는 승차역부터 센다")
    class StartAtBoarding {

        /** 이동 관측 전 사용자 선택 — 승차역(0) 에서 시작한다. */
        private final SubwayRingContext selected = new SubwayRingContext(JOURNEY_ID, 1, 4, 0,
                context.chain(), 0);

        @Test
        @DisplayName("관측이 없어도 첫 링이 나온다 — 다음 역=승차역, 남은 역=탈 정거장 수, 0 고정")
        void firstRingWithoutObservation() {
            SubwayRingState state = calculator.next(null, selected, null, T0);

            assertThat(state.nextStationSequence()).isZero();
            assertThat(state.nextStationName()).isEqualTo("승차역");
            assertThat(state.remainingStations()).isEqualTo(3);
            assertThat(state.segmentProgress()).isEqualByComparingTo("0");
            assertThat(state.progressTarget()).isEqualByComparingTo("0");
            assertThat(state.quality()).isEqualTo(SubwayRingQuality.STALE);
        }

        @Test
        @DisplayName("다가오는 열차 — 도착 예정은 싣되 진행률·보간 상한은 0 이다")
        void approachingTrainKeepsEtaButNoProgress() {
            SubwayRingState first = calculator.next(null, selected,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 0,
                            T0.plusSeconds(120), T0), T0);
            SubwayRingState later = calculator.next(first, selected,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 0,
                            T0.plusSeconds(100), T0.plusSeconds(60)), T0.plusSeconds(60));
            SubwayRingState entering = calculator.next(later, selected,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENTERING, 0, null,
                            T0.plusSeconds(90)), T0.plusSeconds(90));

            assertThat(first.estimatedNextStationAt()).isEqualTo(T0.plusSeconds(120));
            assertThat(first.quality()).isEqualTo(SubwayRingQuality.NORMAL);
            assertThat(later.nextStationSequence()).isZero();
            assertThat(later.segmentProgress()).isEqualByComparingTo("0");
            assertThat(later.progressTarget()).isEqualByComparingTo("0");
            assertThat(later.estimatedNextStationAt()).isEqualTo(T0.plusSeconds(100));
            assertThat(entering.nextStationSequence()).isZero();
            assertThat(entering.segmentProgress()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("승차역 도착 → 곧바로 다음 역 구간 0 이다(승차역 1.00 을 찍지 않는다)")
        void arrivalAtBoardingMovesOn() {
            SubwayRingState state = calculator.next(null, selected,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ARRIVED, 0, null, T0), T0);

            assertThat(state.nextStationSequence()).isEqualTo(1);
            assertThat(state.nextStationName()).isEqualTo("역A");
            assertThat(state.remainingStations()).isEqualTo(3);
            assertThat(state.segmentProgress()).isEqualByComparingTo("0");
            assertThat(state.progressTarget()).isEqualByComparingTo("0.90");
        }

        @Test
        @DisplayName("승차역 출발 → 다음 역 구간으로 넘어간다")
        void departureFromBoardingMovesOn() {
            SubwayRingState state = calculator.next(null, selected,
                    SubwayRingObservation.arrival(SubwayArrivalCode.DEPARTED, 0, null, T0), T0);

            assertThat(state.nextStationSequence()).isEqualTo(1);
            assertThat(state.phase()).isEqualTo(SubwayRingPhase.RIDE_SUBWAY);
        }

        @Test
        @DisplayName("방금 떠난 열차 — 위치가 이미 승차역 뒤면 그 역부터 바로 링이다")
        void departedTrainJumpsAhead() {
            SubwayRingState state = calculator.next(null, selected,
                    SubwayRingObservation.position(SubwayTrainStatus.ARRIVED, 1, T0), T0);

            assertThat(state.nextStationSequence()).isEqualTo(1);
            assertThat(state.segmentProgress()).isEqualByComparingTo("1.00");
        }

        @Test
        @DisplayName("이동 관측으로 고정된 열차는 그대로 승차 다음 역부터다")
        void boundByMovementStartsAfterBoarding() {
            SubwayRingState state = calculator.next(null, context, null, T0);

            assertThat(state.nextStationSequence()).isEqualTo(1);
            assertThat(calculator.targetStationSequence(null, selected)).isZero();
            assertThat(calculator.targetStationSequence(null, context)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("멈춘 링은 이유를 말한다 — 진행률·예정은 그대로")
    class HoldReason {

        @Test
        @DisplayName("전역 진입·정차(4/5) → PREV_STATION_STOPPED, 열차가 선 역 = 다음 역 바로 앞")
        void prevStationStoppedNamesStation() {
            SubwayRingState arrivedAtA = calculator.next(
                    enroute(BigDecimal.valueOf(0.80), T0, T0.plusSeconds(60)), context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ARRIVED, 1, null, T0.plusSeconds(60)),
                    T0.plusSeconds(60));

            for (SubwayArrivalCode code : new SubwayArrivalCode[]{SubwayArrivalCode.PREV_ARRIVED,
                    SubwayArrivalCode.PREV_ENTERING}) {
                SubwayRingState held = calculator.next(arrivedAtA, context,
                        SubwayRingObservation.arrival(code, 2, null, T0.plusSeconds(80)), T0.plusSeconds(80));

                assertThat(held.nextStationName()).isEqualTo("역B");
                assertThat(held.holdReason()).isEqualTo(SubwayRingHoldReason.PREV_STATION_STOPPED);
                assertThat(held.holdStationName()).isEqualTo("역A");
                assertThat(held.segmentProgress()).isEqualByComparingTo("0");
                assertThat(held.estimatedNextStationAt()).isNull();
                assertThat(held.quality()).isEqualTo(SubwayRingQuality.NORMAL);
            }
        }

        @Test
        @DisplayName("승차역 대기 구간 — 앞 역이 체인에 없어 역 이름은 null, 이유는 그대로")
        void boardingWaitHasNoStationName() {
            SubwayRingContext selected = new SubwayRingContext(JOURNEY_ID, 1, 4, 0, context.chain(), 0);

            SubwayRingState held = calculator.next(null, selected,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_ARRIVED, 0, null, T0), T0);

            assertThat(held.nextStationSequence()).isZero();
            assertThat(held.holdReason()).isEqualTo(SubwayRingHoldReason.PREV_STATION_STOPPED);
            assertThat(held.holdStationName()).isNull();
        }

        @Test
        @DisplayName("실측 예정이 지났는데 도착이 안 보이면 LATE")
        void overrunIsLate() {
            SubwayRingState first = calculator.next(null, context,
                    SubwayRingObservation.measured(1, T0, T0.plusSeconds(60), T0.plusSeconds(20)),
                    T0.plusSeconds(25));
            SubwayRingState late = calculator.next(first, context,
                    SubwayRingObservation.measured(1, T0, T0.plusSeconds(60), T0.plusSeconds(70)),
                    T0.plusSeconds(75));

            assertThat(first.holdReason()).isNull();
            assertThat(late.holdReason()).isEqualTo(SubwayRingHoldReason.LATE);
            assertThat(late.holdStationName()).isNull();
        }

        @Test
        @DisplayName("정차 뒤 출발이 보이면 이유가 지워진다")
        void departureClearsHold() {
            SubwayRingState dwell = calculator.next(null, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_ARRIVED, 1, null, T0.minusSeconds(20)),
                    T0.minusSeconds(15));
            SubwayRingState moving = calculator.next(dwell, context,
                    SubwayRingObservation.measured(1, T0, T0.plusSeconds(60), T0.plusSeconds(10)),
                    T0.plusSeconds(12));

            assertThat(dwell.holdReason()).isEqualTo(SubwayRingHoldReason.PREV_STATION_STOPPED);
            assertThat(moving.holdReason()).isNull();
            assertThat(moving.holdStationName()).isNull();
        }

        @Test
        @DisplayName("보간 중·STALE freeze·진입·도착은 이유가 없다")
        void noReasonOtherwise() {
            SubwayRingState held = calculator.next(null, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_ARRIVED, 1, null, T0), T0);

            SubwayRingState interpolating = calculator.next(held, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1, T0.plusSeconds(90), T0.plusSeconds(10)),
                    T0.plusSeconds(10));
            SubwayRingState frozen = calculator.next(held, context, null, T0.plusSeconds(10));
            SubwayRingState entering = calculator.next(held, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENTERING, 1, null, T0.plusSeconds(10)),
                    T0.plusSeconds(10));
            SubwayRingState arrived = calculator.next(held, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ARRIVED, 1, null, T0.plusSeconds(10)),
                    T0.plusSeconds(10));

            assertThat(frozen.quality()).isEqualTo(SubwayRingQuality.STALE);
            for (SubwayRingState state : List.of(interpolating, frozen, entering, arrived)) {
                assertThat(state.holdReason()).isNull();
                assertThat(state.holdStationName()).isNull();
            }
        }
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    /** 역A(순번 1) 로 가는 중인 상태. */
    // ── 화면 단조(#357) ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("같은 구간의 새 링은 지금 화면보다 뒤로 가지 않는다 — push 는 앱 가드를 거치지 않는다(#357)")
    class NeverBehindScreen {

        @Test
        @DisplayName("원천 지연만큼 과거 관측으로 재앵커해도 지금 화면 값에서 이어 그린다")
        void lateObservationDoesNotRewind() {
            SubwayRingState previous = enroute(BigDecimal.valueOf(0.20), T0, T0.plusSeconds(100));
            Instant now = T0.plusSeconds(80);

            // 관측은 20초 전(T0+60) 것이다. 그 시각 기준 현재값은 0.62 지만 앱은 지금 0.76 을 그린다.
            SubwayRingState state = calculator.next(previous, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1,
                            T0.plusSeconds(140), T0.plusSeconds(60)), now);

            assertThat(state.shownProgressAt(now))
                    .isGreaterThanOrEqualTo(previous.shownProgressAt(now));
            assertThat(state.segmentProgress()).isEqualByComparingTo("0.76");
            assertThat(state.progressBaseAt()).isEqualTo(now);
            assertThat(state.estimatedNextStationAt()).isEqualTo(T0.plusSeconds(140));
        }

        @Test
        @DisplayName("멈춘 링(STALE)은 보간하지 않으니 base 그대로가 화면이다 — 올리지 않는다")
        void staleRingIsNotLifted() {
            SubwayRingState frozen = calculator.next(
                    enroute(BigDecimal.valueOf(0.55), T0, T0.plusSeconds(60)), context, null, T0.plusSeconds(30));

            SubwayRingState again = calculator.next(frozen, context, null, T0.plusSeconds(200));

            assertThat(again.segmentProgress()).isEqualByComparingTo(frozen.segmentProgress());
            assertThat(again.progressBaseAt()).isEqualTo(frozen.progressBaseAt());
        }

        @Test
        @DisplayName("구간이 바뀌면 0 에서 시작한다 — 다른 구간의 화면 값과 견주지 않는다")
        void newSegmentStartsAtZero() {
            SubwayRingState previous = enroute(BigDecimal.valueOf(0.80), T0, T0.plusSeconds(60));

            SubwayRingState state = calculator.next(previous, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.DEPARTED, 1, null, T0.plusSeconds(50)),
                    T0.plusSeconds(50));

            assertThat(state.nextStationSequence()).isEqualTo(2);
            assertThat(state.segmentProgress()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("tick 마다 화면 값이 줄지 않는다 — 관측·멈춤·재앵커가 섞여도")
        void shownProgressIsMonotonicAcrossTicks() {
            SubwayRingState state = calculator.next(null, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 1, T0.plusSeconds(120), T0), T0);
            SubwayRingObservation[] ticks = {
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1, T0.plusSeconds(150), T0.plusSeconds(10)),
                    null,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_ARRIVED, 1, null, T0.plusSeconds(30)),
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 1, T0.plusSeconds(100), T0.plusSeconds(40)),
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1, null, T0.plusSeconds(60)),
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1, T0.plusSeconds(200), T0.plusSeconds(70)),
            };
            for (int i = 0; i < ticks.length; i++) {
                Instant now = T0.plusSeconds(15L * (i + 1) + 10);
                BigDecimal before = state.shownProgressAt(now);
                state = calculator.next(state, context, ticks[i], now);
                assertThat(state.nextStationSequence()).isEqualTo(1);
                assertThat(state.shownProgressAt(now)).as("tick %d", i).isGreaterThanOrEqualTo(before);
            }
        }
    }

    // ── 예정 출처·단계·근거 시각(TYPICAL 계약 PR-1) ─────────────────────────────

    @Nested
    @DisplayName("예정 출처·단계·근거 시각을 싣는다 — 화면 값은 그대로")
    class SourceAndStage {

        @Test
        @DisplayName("운행 중 남은 초 → LIVE·RUN, 실측 중앙값 → MEASURED·RUN")
        void liveAndMeasured() {
            SubwayRingState live = calculator.next(enroute(BigDecimal.valueOf(0.20), T0, T0.plusSeconds(100)),
                    context, SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1, T0.plusSeconds(120),
                            T0.plusSeconds(10)), T0.plusSeconds(10));
            assertThat(live.etaSource()).isEqualTo(SubwayRingEtaSource.LIVE);
            assertThat(live.stage()).isEqualTo(SubwayRingStage.RUN);

            SubwayRingState measured = calculator.next(null, context,
                    SubwayRingObservation.measured(1, T0, T0.plusSeconds(90), T0.plusSeconds(5)), T0.plusSeconds(5));
            assertThat(measured.etaSource()).isEqualTo(SubwayRingEtaSource.MEASURED);
            assertThat(measured.stage()).isEqualTo(SubwayRingStage.RUN);
        }

        @Test
        @DisplayName("예정이 없는 화면(정차·진입·도착·늦음·멈춤)은 출처가 없다. 단계는 정차·진입·도착·늦음")
        void noEtaNoSource() {
            SubwayRingState previous = enroute(BigDecimal.valueOf(0.40), T0, T0.plusSeconds(100));

            SubwayRingState dwell = calculator.next(previous, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_ARRIVED, 1, null, T0.plusSeconds(20)),
                    T0.plusSeconds(20));
            SubwayRingState approach = calculator.next(previous, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENTERING, 1, null, T0.plusSeconds(20)),
                    T0.plusSeconds(20));
            SubwayRingState arrived = calculator.next(previous, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ARRIVED, 1, null, T0.plusSeconds(20)),
                    T0.plusSeconds(20));
            SubwayRingState late = calculator.next(null, context,
                    SubwayRingObservation.measured(1, T0, T0.plusSeconds(60), T0.plusSeconds(90)),
                    T0.plusSeconds(90));
            SubwayRingState frozen = calculator.next(previous, context, null, T0.plusSeconds(20));

            assertThat(List.of(dwell, approach, arrived, late, frozen))
                    .allSatisfy(s -> assertThat(s.etaSource()).isNull());
            assertThat(dwell.stage()).isEqualTo(SubwayRingStage.DWELL);
            assertThat(approach.stage()).isEqualTo(SubwayRingStage.APPROACH);
            assertThat(arrived.stage()).isEqualTo(SubwayRingStage.ARRIVED);
            assertThat(late.stage()).isEqualTo(SubwayRingStage.LATE);
            assertThat(frozen.stage()).as("멈춤은 직전 단계를 이어 간다").isEqualTo(previous.stage());
        }

        @Test
        @DisplayName("승차역 도착은 곧바로 다음 구간 시작이다 — 새 구간에 ARRIVED 를 붙이지 않는다")
        void arrivalAtBoardingStartsRun() {
            SubwayRingContext selected = new SubwayRingContext(JOURNEY_ID, 1, 4, 0, context.chain(), 0);

            SubwayRingState state = calculator.next(null, selected,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ARRIVED, 0, null, T0), T0);

            assertThat(state.nextStationSequence()).isEqualTo(1);
            assertThat(state.stage()).isEqualTo(SubwayRingStage.RUN);
        }

        @Test
        @DisplayName("근거 시각: 관측의 원천 시각과 서버가 처음 본 시각을 싣고, 멈춤은 직전 근거를 이어 간다")
        void sourceTimes() {
            Instant recptn = T0.minusSeconds(90);
            SubwayRingState observed = calculator.next(null, context,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1, T0.plusSeconds(60), recptn)
                            .withSeenAt(T0), T0);
            assertThat(observed.sourceObservedAt()).isEqualTo(recptn);
            assertThat(observed.sourceSeenAt()).isEqualTo(T0);

            SubwayRingState frozen = calculator.next(observed, context, null, T0.plusSeconds(200));
            assertThat(frozen.sourceObservedAt()).isEqualTo(recptn);
            assertThat(frozen.sourceSeenAt()).isEqualTo(T0);
        }

        @Test
        @DisplayName("관측·멈춤이 섞인 tick 열 내내 '예정 없음 ⇔ 출처 없음'")
        void sourceIffEta() {
            SubwayRingState state = null;
            SubwayRingObservation[] ticks = {
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 1, null, T0),
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1, T0.plusSeconds(100), T0.plusSeconds(10)),
                    null,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_ARRIVED, 1, null, T0.plusSeconds(30)),
                    SubwayRingObservation.measured(1, T0.plusSeconds(40), T0.plusSeconds(120), T0.plusSeconds(45)),
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENTERING, 1, null, T0.plusSeconds(110)),
                    SubwayRingObservation.arrival(SubwayArrivalCode.ARRIVED, 1, null, T0.plusSeconds(120)),
                    SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 2, T0.plusSeconds(220), T0.plusSeconds(130)),
            };
            for (int i = 0; i < ticks.length; i++) {
                state = calculator.next(state, context, ticks[i], T0.plusSeconds(15L * i + 5));
                assertThat(state.etaSource() == null).as("tick %d", i)
                        .isEqualTo(state.estimatedNextStationAt() == null);
            }
        }
    }

    // ── TYPICAL 직선·서버 시계 STALE·LATE 유예(PR-2) ───────────────────────────

    @Nested
    @DisplayName("전형 시간 직선 — 정상 구간 전환은 STALE 이 아니다")
    class TypicalSegment {

        /** 모든 구간: 직전 역 정차 30초, 운행 90초. ×1.2 → 운행 108초, 정차+운행 144초. */
        private final SubwayRingContext typical = context.withTypical(seq -> new SubwayRingContext.Typical(30, 90));

        private SubwayRingObservation seen(SubwayRingObservation o, Instant seenAt) {
            return o.withSeenAt(seenAt).withPresentAt(seenAt);
        }

        @Test
        @DisplayName("다음 역 출발(2) → 새 구간을 서버가 본 시각부터 운행×1.2 직선으로, NORMAL·TYPICAL·RUN")
        void departureStartsTypicalRun() {
            Instant now = T0.plusSeconds(100);
            SubwayRingState state = calculator.next(enroute(BigDecimal.valueOf(0.80), T0, T0.plusSeconds(60)), typical,
                    seen(SubwayRingObservation.arrival(SubwayArrivalCode.DEPARTED, 1, null, now.minusSeconds(90)), now),
                    now);

            assertThat(state.nextStationSequence()).isEqualTo(2);
            assertThat(state.quality()).isEqualTo(SubwayRingQuality.NORMAL);
            assertThat(state.etaSource()).isEqualTo(SubwayRingEtaSource.TYPICAL);
            assertThat(state.stage()).isEqualTo(SubwayRingStage.RUN);
            assertThat(state.progressBaseAt()).as("원천 시각이 아니라 서버가 본 시각").isEqualTo(now);
            assertThat(state.estimatedNextStationAt()).isEqualTo(now.plusSeconds(108));
            assertThat(state.shownProgressAt(now)).as("그리자마자 점프하지 않는다").isEqualByComparingTo("0");
            assertThat(state.progressValidUntil()).isAfter(state.estimatedNextStationAt());
        }

        @Test
        @DisplayName("위치 폴백: 출발이면 TYPICAL 운행 직선, 도착·진입은 NORMAL(사실이다)")
        void positionFallback() {
            Instant now = T0.plusSeconds(40);
            SubwayRingState departed = calculator.next(null, typical,
                    seen(SubwayRingObservation.position(SubwayTrainStatus.DEPARTED, 1, now.minusSeconds(30)), now), now);
            assertThat(departed.nextStationSequence()).isEqualTo(2);
            assertThat(departed.quality()).isEqualTo(SubwayRingQuality.NORMAL);
            assertThat(departed.etaSource()).isEqualTo(SubwayRingEtaSource.TYPICAL);

            SubwayRingState arrived = calculator.next(null, typical,
                    SubwayRingObservation.position(SubwayTrainStatus.ARRIVED, 1, now.minusSeconds(30)), now);
            assertThat(arrived.quality()).isEqualTo(SubwayRingQuality.NORMAL);
        }

        @Test
        @DisplayName("승차역 도착 → 다음 구간을 정차 직선부터: 정차 지점 0.225(=0.90×30/120)까지 36초, TYPICAL·DWELL")
        void boardingArrivalIncludesDwell() {
            SubwayRingContext selected = new SubwayRingContext(JOURNEY_ID, 1, 4, 0, context.chain(), 0)
                    .withTypical(seq -> new SubwayRingContext.Typical(30, 90));

            SubwayRingState state = calculator.next(null, selected,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ARRIVED, 0, null, T0), T0);

            assertThat(state.nextStationSequence()).isEqualTo(1);
            assertThat(state.etaSource()).isEqualTo(SubwayRingEtaSource.TYPICAL);
            assertThat(state.stage()).isEqualTo(SubwayRingStage.DWELL);
            assertThat(state.estimatedNextStationAt()).isEqualTo(T0.plusSeconds(36));
            assertThat(state.progressTarget()).isEqualByComparingTo("0.225");
            assertThat(state.holdReason()).isEqualTo(SubwayRingHoldReason.PREV_STATION_STOPPED);
        }

        @Test
        @DisplayName("전역 출발 + 실측 표본 없음 → TYPICAL 운행 직선. 같은 행이 10 tick 반복돼도 앵커·예정이 그대로다")
        void sameObservationKeepsTheLine() {
            Instant first = T0.plusSeconds(5);
            SubwayRingObservation row = SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 1, null,
                    T0.minusSeconds(80)).withSeenAt(first);
            SubwayRingState state = calculator.next(null, typical, row.withPresentAt(first), first);
            assertThat(state.etaSource()).isEqualTo(SubwayRingEtaSource.TYPICAL);
            Instant eta = state.estimatedNextStationAt();
            Instant baseAt = state.progressBaseAt();

            for (int tick = 1; tick <= 10; tick++) {
                Instant now = first.plusSeconds(5L * tick);
                state = calculator.next(state, typical, row.withPresentAt(now), now);
                assertThat(state.estimatedNextStationAt()).as("tick %d", tick).isEqualTo(eta);
                assertThat(state.progressBaseAt()).as("tick %d", tick).isEqualTo(baseAt);
                assertThat(state.quality()).isEqualTo(SubwayRingQuality.NORMAL);
            }
        }

        @Test
        @DisplayName("TYPICAL 예정 + 유예(30초)가 지나도 진입이 없으면 상한에서 LATE")
        void typicalOverrunIsLate() {
            Instant first = T0;
            SubwayRingObservation row = SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 1, null,
                    T0.minusSeconds(80)).withSeenAt(first);
            SubwayRingState state = calculator.next(null, typical, row.withPresentAt(first), first);

            Instant beforeGrace = first.plusSeconds(108 + 20);
            SubwayRingState waiting = calculator.next(state, typical, row.withPresentAt(beforeGrace), beforeGrace);
            assertThat(waiting.holdReason()).isNull();

            Instant afterGrace = first.plusSeconds(108 + 31);
            SubwayRingState late = calculator.next(waiting, typical, row.withPresentAt(afterGrace), afterGrace);
            assertThat(late.holdReason()).isEqualTo(SubwayRingHoldReason.LATE);
            assertThat(late.stage()).isEqualTo(SubwayRingStage.LATE);
            assertThat(late.segmentProgress()).isEqualByComparingTo("0.90");
        }

        @Test
        @DisplayName("실측(MEASURED)도 서버가 본 시각에 앵커 — 원천 지연 90초·중앙값 60초인 짧은 구간이 첫 관측에 LATE 가 아니다(journey 202)")
        void measuredAnchorsAtServerTime() {
            Instant now = T0.plusSeconds(200);
            Instant recptn = now.minusSeconds(90);
            SubwayRingObservation measured = SubwayRingObservation.measured(1, recptn.minusSeconds(5),
                    recptn.plusSeconds(55), recptn).withSeenAt(now).withPresentAt(now);

            SubwayRingState state = calculator.next(null, typical, measured, now);

            assertThat(state.holdReason()).isNull();
            assertThat(state.etaSource()).isEqualTo(SubwayRingEtaSource.MEASURED);
            assertThat(state.progressBaseAt()).isEqualTo(now);
            assertThat(state.estimatedNextStationAt()).isEqualTo(now.plusSeconds(60));
            assertThat(state.shownProgressAt(now)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("STALE 은 서버 시계: 원천이 90초 늦어도 방금 받았으면 아니고, 60초 못 받았거나 원천이 240초 넘게 묵었으면 STALE")
        void staleByServerClock() {
            SubwayRingState previous = enroute(BigDecimal.valueOf(0.30), T0, T0.plusSeconds(200));
            Instant now = T0.plusSeconds(100);
            SubwayRingObservation enrouteRow = SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1,
                    now.plusSeconds(100), now.minusSeconds(90)).withSeenAt(now.minusSeconds(5));

            assertThat(calculator.next(previous, typical, enrouteRow.withPresentAt(now.minusSeconds(10)), now).quality())
                    .isEqualTo(SubwayRingQuality.NORMAL);
            assertThat(calculator.next(previous, typical, enrouteRow.withPresentAt(now.minusSeconds(61)), now).quality())
                    .as("수집이 끊겼다").isEqualTo(SubwayRingQuality.STALE);

            SubwayRingObservation frozenRow = SubwayRingObservation.arrival(SubwayArrivalCode.ENROUTE, 1,
                    now.plusSeconds(100), now.minusSeconds(241)).withSeenAt(now.minusSeconds(241));
            SubwayRingState frozen = calculator.next(previous, typical, frozenRow.withPresentAt(now), now);
            assertThat(frozen.quality()).as("수집은 살아 있지만 원천이 굳었다").isEqualTo(SubwayRingQuality.STALE);
            assertThat(frozen.estimatedNextStationAt()).as("예정이 밀리지 않고 비워진다").isNull();
        }

        @Test
        @DisplayName("전형 시간을 모르면 전환은 예전처럼 0 STALE")
        void unknownTypicalKeepsOldBehaviour() {
            Instant now = T0.plusSeconds(100);
            SubwayRingState state = calculator.next(enroute(BigDecimal.valueOf(0.80), T0, T0.plusSeconds(60)), context,
                    seen(SubwayRingObservation.arrival(SubwayArrivalCode.DEPARTED, 1, null, now.minusSeconds(90)), now),
                    now);

            assertThat(state.nextStationSequence()).isEqualTo(2);
            assertThat(state.quality()).isEqualTo(SubwayRingQuality.STALE);
            assertThat(state.etaSource()).isNull();
        }
    }

    // ── 전역 정차를 같은 직선에(PR-3) ──────────────────────────────────────────

    @Nested
    @DisplayName("전역 정차 — 같은 구간 직선의 앞부분으로 그린다")
    class DwellLine {

        /** 정차 30초, 운행 90초 → 정차 지점 0.90×30/120 = 0.225, 정차 36초·운행 108초(×1.2). */
        private final SubwayRingContext typical = context.withTypical(seq -> new SubwayRingContext.Typical(30, 90));

        private SubwayRingObservation prevArrived(Instant seenAt, Instant now) {
            return SubwayRingObservation.arrival(SubwayArrivalCode.PREV_ARRIVED, 1, null, seenAt.minusSeconds(80))
                    .withSeenAt(seenAt).withPresentAt(now);
        }

        @Test
        @DisplayName("첫 전역 도착 → NORMAL 정차 직선 0 → 0.225, 예정 = 정차 시작 + 36초, TYPICAL·DWELL, '승차역 정차 중'")
        void firstDwellObservation() {
            SubwayRingState state = calculator.next(null, typical, prevArrived(T0, T0), T0);

            assertThat(state.quality()).isEqualTo(SubwayRingQuality.NORMAL);
            assertThat(state.segmentProgress()).isEqualByComparingTo("0");
            assertThat(state.progressTarget()).isEqualByComparingTo("0.225");
            assertThat(state.estimatedNextStationAt()).isEqualTo(T0.plusSeconds(36));
            assertThat(state.etaSource()).isEqualTo(SubwayRingEtaSource.TYPICAL);
            assertThat(state.stage()).isEqualTo(SubwayRingStage.DWELL);
            assertThat(state.holdReason()).isEqualTo(SubwayRingHoldReason.PREV_STATION_STOPPED);
            assertThat(state.holdStationName()).isEqualTo("승차역");
        }

        @Test
        @DisplayName("같은 전역 도착이 반복돼도 정차 시작을 다시 잡지 않는다. 정차가 길어지면 정차 지점에서 멈춘다(사유 유지)")
        void repeatedAndOverrunDwell() {
            SubwayRingState state = calculator.next(null, typical, prevArrived(T0, T0), T0);
            for (int tick = 1; tick <= 6; tick++) {
                Instant now = T0.plusSeconds(5L * tick);
                state = calculator.next(state, typical, prevArrived(T0, now), now);
                assertThat(state.progressBaseAt()).as("tick %d", tick).isEqualTo(T0);
                assertThat(state.estimatedNextStationAt()).as("tick %d", tick).isEqualTo(T0.plusSeconds(36));
            }
            Instant overrun = T0.plusSeconds(50);
            SubwayRingState held = calculator.next(state, typical, prevArrived(T0, overrun), overrun);
            assertThat(held.segmentProgress()).isEqualByComparingTo("0.225");
            assertThat(held.estimatedNextStationAt()).isNull();
            assertThat(held.holdReason()).isEqualTo(SubwayRingHoldReason.PREV_STATION_STOPPED);
            assertThat(held.quality()).isEqualTo(SubwayRingQuality.NORMAL);

            Instant later = T0.plusSeconds(90);
            SubwayRingState still = calculator.next(held, typical, prevArrived(T0, later), later);
            assertThat(still.segmentProgress()).isEqualByComparingTo("0.225");
            assertThat(still.holdReason()).isEqualTo(SubwayRingHoldReason.PREV_STATION_STOPPED);
        }

        @Test
        @DisplayName("정시 출발이면 정차 직선과 운행 직선의 기울기가 같다 — 꺾이지도 되감기지도 않는다")
        void onScheduleIsOneStraightLine() {
            SubwayRingState dwell = calculator.next(null, typical, prevArrived(T0, T0), T0);
            Instant departSeen = T0.plusSeconds(36);
            SubwayRingState run = calculator.next(dwell, typical,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, 1, null, departSeen.minusSeconds(80))
                            .withSeenAt(departSeen).withPresentAt(departSeen), departSeen);

            assertThat(run.stage()).isEqualTo(SubwayRingStage.RUN);
            assertThat(run.etaSource()).isEqualTo(SubwayRingEtaSource.TYPICAL);
            assertThat(run.holdReason()).isNull();
            assertThat(run.shownProgressAt(departSeen)).isEqualByComparingTo("0.225");
            assertThat(run.estimatedNextStationAt()).isEqualTo(departSeen.plusSeconds(108));
            // 0.225 / 36초 = 0.675 / 108초
            BigDecimal dwellSlope = dwell.shownProgressAt(T0.plusSeconds(36)).divide(BigDecimal.valueOf(36), 6,
                    java.math.RoundingMode.HALF_UP);
            BigDecimal runSlope = run.shownProgressAt(departSeen.plusSeconds(108)).subtract(new BigDecimal("0.225"))
                    .divide(BigDecimal.valueOf(108), 6, java.math.RoundingMode.HALF_UP);
            assertThat(runSlope).isEqualByComparingTo(dwellSlope);
        }

        @Test
        @DisplayName("이미 출발(운행)을 본 뒤 늦게 온 전역 도착은 무시한다 — 정차로 돌아가 멈추지 않는다(리뷰 R4)")
        void lateDwellAfterRunIsIgnored() {
            Instant now = T0.plusSeconds(10);
            // 위치로 역A(1) 출발을 봤다 → 역B(2) 구간 운행 직선.
            SubwayRingState run = calculator.next(null, typical,
                    SubwayRingObservation.position(SubwayTrainStatus.DEPARTED, 1, T0).withSeenAt(T0), now);
            assertThat(run.nextStationSequence()).isEqualTo(2);
            assertThat(run.stage()).isEqualTo(SubwayRingStage.RUN);

            // 도착 API 가 늦게 "역A 도착"(역B 기준 전역 도착)을 준다.
            Instant later = T0.plusSeconds(20);
            SubwayRingState after = calculator.next(run, typical,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_ARRIVED, 2, null, T0.minusSeconds(60))
                            .withSeenAt(later).withPresentAt(later), later);
            assertThat(after.stage()).isEqualTo(SubwayRingStage.RUN);
            assertThat(after.holdReason()).isNull();
            assertThat(after.estimatedNextStationAt()).isEqualTo(run.estimatedNextStationAt());
        }

        @Test
        @DisplayName("직전 링이 바로 그 역 도착이면 정차 시작 = 그 도착을 처음 본 시각(한 tick 늦지 않게)")
        void dwellStartsAtPreviousArrival() {
            Instant arrivedSeen = T0;
            SubwayRingState arrived = calculator.next(enroute(BigDecimal.valueOf(0.80), T0.minusSeconds(60), T0), typical,
                    SubwayRingObservation.arrival(SubwayArrivalCode.ARRIVED, 1, null, T0.minusSeconds(80))
                            .withSeenAt(arrivedSeen).withPresentAt(arrivedSeen), arrivedSeen);
            assertThat(arrived.arrivedAtNextStation()).isTrue();

            Instant now = T0.plusSeconds(5);
            SubwayRingState dwell = calculator.next(arrived, typical,
                    SubwayRingObservation.arrival(SubwayArrivalCode.PREV_ARRIVED, 2, null, T0.minusSeconds(75))
                            .withSeenAt(now).withPresentAt(now), now);

            assertThat(dwell.nextStationSequence()).isEqualTo(2);
            assertThat(dwell.progressBaseAt()).isEqualTo(arrivedSeen);
            assertThat(dwell.holdStationName()).isEqualTo("역A");
        }
    }

    private SubwayRingState enroute(BigDecimal progress, Instant baseAt, Instant estimatedAt) {
        return new SubwayRingState(JOURNEY_ID, 1, 4, 0,
                1, "1002000201", "역A", "하차역", 3,
                progress, baseAt, BigDecimal.valueOf(0.90), estimatedAt,
                baseAt.plus(properties.getFreshnessWindow()),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, baseAt, baseAt);
    }
}
