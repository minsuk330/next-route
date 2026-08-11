package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.RerouteTriggerSignal;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 방아쇠 판정의 계약. <b>이 PR 의 전부가 여기 있다.</b>
 *
 * <p>무게중심은 "켜지지 않는 경우"에 있다. 잘 가는 사용자마다 방아쇠가 켜지면 그것이 곧 외부
 * 검색 요금이고, 실시간 API 가 흔들릴 때 켜지면 장애 한 번이 전 사용자 재탐색이 된다.
 */
@DisplayName("재탐색 방아쇠 판정")
class RerouteTriggerPolicyTest {

    private static final Long JOURNEY_ID = 42L;
    private static final int REVISION = 1;
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    private final JourneyRerouteProperties properties = new JourneyRerouteProperties();
    private final RerouteTriggerPolicy policy = new RerouteTriggerPolicy(properties);

    // ── 모르는 것은 나빠진 것이 아니다 ──────────────────────────────────────

    @Nested
    @DisplayName("모르는 상태")
    class UnknownIsNotWorse {

        @Test
        @DisplayName("새 상태가 UNKNOWN 이면 어떤 전이도 방아쇠가 아니다")
        void unknownIsNeverATrigger() {
            List<RerouteTriggerSignal> signals = signals(
                    at(TransferStatus.CATCHABLE), at(TransferStatus.UNKNOWN));

            // severity 로는 CATCHABLE(1) → UNKNOWN(2) 이라 '나빠졌다'로 읽힌다. 그렇게 읽으면
            // 실시간 API 가 흔들릴 때마다 전 사용자에게 재탐색이 돈다. 이 단언이 이 파일의 존재 이유다.
            assertThat(signals)
                    .as("API 흔들림이 전 사용자 재탐색이 되면 안 된다")
                    .isEmpty();
        }

        @Test
        @DisplayName("근거 품질이 UNKNOWN 이면 문이 닫힌다")
        void unknownConfidenceClosesTheWindow() {
            TransferRiskAssessment current = new TransferRiskAssessment(
                    0, 0, 2, TransferStatus.AT_RISK, NOW.plusSeconds(60), NOW.plusSeconds(120),
                    NOW.plusSeconds(150), 30L, CurrentLegArrival.Quality.UNKNOWN, NOW, null, null);

            assertThat(signals(at(TransferStatus.COMFORTABLE), current)).isEmpty();
        }

        @Test
        @DisplayName("직전이 UNKNOWN 이었다가 근거를 찾아 나쁘면 방아쇠다")
        void recoveringFromUnknownIntoRiskIsATrigger() {
            List<RerouteTriggerSignal> signals = signals(
                    at(TransferStatus.UNKNOWN), at(TransferStatus.AT_RISK));

            assertThat(signals).singleElement()
                    .as("새 상태에 근거가 있고 나쁘다. 모르던 것이 밝혀진 것은 정상 신호다")
                    .extracting(RerouteTriggerSignal::reason)
                    .isEqualTo(RerouteTriggerSignal.Reason.DEGRADED);
        }
    }

    // ── 하락 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("상태 하락")
    class Degraded {

        @Test
        @DisplayName("COMFORTABLE → AT_RISK 는 방아쇠다")
        void worseningIntoRiskFires() {
            assertThat(signals(at(TransferStatus.COMFORTABLE), at(TransferStatus.AT_RISK)))
                    .singleElement()
                    .satisfies(signal -> {
                        assertThat(signal.reason()).isEqualTo(RerouteTriggerSignal.Reason.DEGRADED);
                        assertThat(signal.previousStatus()).isEqualTo(TransferStatus.COMFORTABLE);
                        assertThat(signal.currentStatus()).isEqualTo(TransferStatus.AT_RISK);
                        assertThat(signal.journeyId()).isEqualTo(JOURNEY_ID);
                        assertThat(signal.routeRevision()).isEqualTo(REVISION);
                    });
        }

        @Test
        @DisplayName("AT_RISK → MISSED 도 방아쇠다 — 승격도 하락이다")
        void promotionAlsoFires() {
            assertThat(signals(at(TransferStatus.AT_RISK), at(TransferStatus.MISSED)))
                    .singleElement()
                    .extracting(RerouteTriggerSignal::reason)
                    .isEqualTo(RerouteTriggerSignal.Reason.DEGRADED);
        }

        @Test
        @DisplayName("좋아지면 방아쇠가 아니다")
        void improvementDoesNotFire() {
            assertThat(signals(at(TransferStatus.AT_RISK), at(TransferStatus.CATCHABLE))).isEmpty();
        }

        @Test
        @DisplayName("그대로면 방아쇠가 아니다 — 10초마다 다시 찾지 않는다")
        void unchangedDoesNotFire() {
            assertThat(signals(at(TransferStatus.AT_RISK), at(TransferStatus.AT_RISK))).isEmpty();
        }

        @Test
        @DisplayName("나빠져도 도착지가 위험이 아니면 방아쇠가 아니다")
        void worseningIntoSafeStateDoesNotFire() {
            assertThat(signals(at(TransferStatus.COMFORTABLE), at(TransferStatus.CATCHABLE)))
                    .isEmpty();
        }
    }

    // ── 통과 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("타려던 차가 지나감")
    class TargetPassed {

        @Test
        @DisplayName("직전 판정의 도착 시각이 지났으면 방아쇠다")
        void passedTargetFires() {
            TransferRiskAssessment before = withNextBus(
                    TransferStatus.CATCHABLE, NOW.minusSeconds(30));

            assertThat(signals(before, at(TransferStatus.CATCHABLE)))
                    .singleElement()
                    .extracting(RerouteTriggerSignal::reason)
                    .isEqualTo(RerouteTriggerSignal.Reason.TARGET_PASSED);
        }

        @Test
        @DisplayName("보는 것은 직전 판정의 도착 시각이다 — 이번 판정의 값이 아니다")
        void usesPreviousNextBusArrival() {
            // 직전은 아직 안 지났고, 이번 판정에만 지난 값이 들어 있다.
            TransferRiskAssessment before = withNextBus(
                    TransferStatus.CATCHABLE, NOW.plusSeconds(120));
            TransferRiskAssessment current = withNextBus(
                    TransferStatus.CATCHABLE, NOW.minusSeconds(30));

            assertThat(signals(before, current))
                    .as("이번 값은 다시 계산한 '새로 잡힌 차'라 타려던 차가 아니다")
                    .isEmpty();
        }

        @Test
        @DisplayName("다음 차로 여유 있게 가면 방아쇠가 아니다")
        void comfortableAfterMissIsNotATrigger() {
            TransferRiskAssessment before = withNextBus(
                    TransferStatus.CATCHABLE, NOW.minusSeconds(30));

            assertThat(signals(before, at(TransferStatus.COMFORTABLE)))
                    .as("찾아 봐야 최소 개선폭을 못 넘긴다. 아무것도 못 내놓고 요금만 쓴다")
                    .isEmpty();
        }

        @Test
        @DisplayName("다음 차가 빠듯하면 방아쇠다")
        void catchableAfterMissStillFires() {
            TransferRiskAssessment before = withNextBus(
                    TransferStatus.COMFORTABLE, NOW.minusSeconds(30));

            assertThat(signals(before, at(TransferStatus.CATCHABLE)))
                    .as("서두르면 되는 상황은 사용자가 다른 길을 볼 값이 있다")
                    .singleElement()
                    .extracting(RerouteTriggerSignal::reason)
                    .isEqualTo(RerouteTriggerSignal.Reason.TARGET_PASSED);
        }

        @Test
        @DisplayName("직전이 UNKNOWN 이면 통과 판정을 하지 않는다")
        void unknownPreviousHasNoTarget() {
            TransferRiskAssessment before = new TransferRiskAssessment(
                    0, 0, 2, TransferStatus.UNKNOWN, null, null, NOW.minusSeconds(30), null,
                    CurrentLegArrival.Quality.UNKNOWN, NOW, null, null);

            assertThat(signals(before, at(TransferStatus.CATCHABLE))).isEmpty();
        }

        @Test
        @DisplayName("하락과 통과가 겹치면 하락 하나만 낸다")
        void degradedWinsOverPassed() {
            TransferRiskAssessment before = withNextBus(
                    TransferStatus.COMFORTABLE, NOW.minusSeconds(30));

            assertThat(signals(before, at(TransferStatus.AT_RISK)))
                    .as("신호를 둘 내면 cooldown 이 하나를 삼켜 지표가 사유별로 어긋난다")
                    .singleElement()
                    .extracting(RerouteTriggerSignal::reason)
                    .isEqualTo(RerouteTriggerSignal.Reason.DEGRADED);
        }
    }

    // ── 문 ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("문(decision window)")
    class Window {

        @Test
        @DisplayName("환승이 멀면 어떤 하락도 방아쇠가 아니다")
        void farTransferKeepsTheWindowClosed() {
            TransferRiskAssessment current = readyAt(TransferStatus.AT_RISK, NOW.plusSeconds(700));

            assertThat(signals(at(TransferStatus.COMFORTABLE), current))
                    .as("문과 방아쇠를 섞으면 잘 가는 사용자마다 외부 검색이 나간다")
                    .isEmpty();
        }

        @Test
        @DisplayName("경계(600초)는 열려 있다")
        void windowBoundaryIsInclusive() {
            TransferRiskAssessment current = readyAt(TransferStatus.AT_RISK, NOW.plusSeconds(600));

            assertThat(signals(at(TransferStatus.COMFORTABLE), current)).hasSize(1);
        }

        @Test
        @DisplayName("GPS 가 끊겨 userReadyAt 이 없으면 문이 닫힌다")
        void missingProgressClosesTheWindow() {
            TransferRiskAssessment current = readyAt(TransferStatus.AT_RISK, null);

            assertThat(signals(at(TransferStatus.COMFORTABLE), current))
                    .as("낡은 값으로 문을 열면 엉뚱한 때에 외부 검색이 나간다")
                    .isEmpty();
        }

        @Test
        @DisplayName("이미 지나온 환승은 보지 않는다")
        void pastTransferIsSkipped() {
            List<RerouteTriggerSignal> signals = policy.signals(JOURNEY_ID, REVISION,
                    Map.of(0, at(TransferStatus.COMFORTABLE)), List.of(at(TransferStatus.AT_RISK)),
                    /* currentLegIndex */ 5, NOW);

            assertThat(signals).isEmpty();
        }
    }

    // ── 첫 평가 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("직전 판정이 없으면 방아쇠가 아니다 — 여정 시작마다 켜지면 안 된다")
    void firstEvaluationDoesNotFire() {
        List<RerouteTriggerSignal> signals = policy.signals(JOURNEY_ID, REVISION,
                Map.of(), List.of(at(TransferStatus.AT_RISK)), 0, NOW);

        assertThat(signals)
                .as("직전 없음을 UNKNOWN 으로 취급하면 모든 여정이 시작하자마자 한 번씩 켠다")
                .isEmpty();
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private List<RerouteTriggerSignal> signals(TransferRiskAssessment before,
                                               TransferRiskAssessment current) {
        return policy.signals(JOURNEY_ID, REVISION, Map.of(0, before), List.of(current), 0, NOW);
    }

    /** 문이 열려 있는 기본 판정. 환승은 2분 뒤다. */
    private TransferRiskAssessment at(TransferStatus status) {
        return readyAt(status, NOW.plusSeconds(120));
    }

    private TransferRiskAssessment readyAt(TransferStatus status, Instant userReadyAt) {
        return new TransferRiskAssessment(0, 0, 2, status, NOW.plusSeconds(60), userReadyAt,
                NOW.plusSeconds(180), 60L, CurrentLegArrival.Quality.GOOD, NOW,
                CurrentLegArrival.Source.REALTIME_ARRIVAL, null);
    }

    private TransferRiskAssessment withNextBus(TransferStatus status, Instant nextBusArrivalAt) {
        return new TransferRiskAssessment(0, 0, 2, status, NOW.plusSeconds(60),
                NOW.plusSeconds(120), nextBusArrivalAt, 60L, CurrentLegArrival.Quality.GOOD, NOW,
                CurrentLegArrival.Source.REALTIME_ARRIVAL, null);
    }
}
