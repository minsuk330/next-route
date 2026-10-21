package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationIntervalPolicy.Decision;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationIntervalPolicy.Reason;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;

import watoo.grd.nextroute.application.journey.reeval.ReevaluationTier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다음 평가까지 얼마나 기다릴지 정하는 규칙을 표 그대로 확인한다.
 *
 * <p>기본값 기준 — 최소 10초, 기본 30초, 한가할 때 120초, 최대 180초. 임박 기준 5분, 한가함 기준
 * 15분, 변화 없을 때 2배.
 */
@DisplayName("평가 간격 규칙")
class JourneyEvaluationIntervalPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-05T09:00:00Z");
    private static final int CURRENT_LEG = 1;

    private JourneyEvaluationIntervalPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new JourneyEvaluationIntervalPolicy(new JourneyEvaluatorProperties());
    }

    /** 환승 하나. 갈아탈 대기 단계는 현재 단계보다 뒤에 둔다(아직 남은 환승). */
    private TransferRiskAssessment transfer(TransferStatus status, Duration untilReady) {
        return assessment(status, untilReady, CURRENT_LEG + 1);
    }

    private TransferRiskAssessment assessment(TransferStatus status, Duration untilReady,
                                              int toLegIndex) {
        return new TransferRiskAssessment(0, toLegIndex - 1, toLegIndex, status,
                NOW.plus(untilReady), NOW.plus(untilReady), NOW.plus(untilReady).plusSeconds(120),
                120L, CurrentLegArrival.Quality.GOOD, NOW, CurrentLegArrival.Source.REALTIME_ARRIVAL,
                null);
    }

    private Decision decide(List<TransferRiskAssessment> assessments, Duration previousInterval,
                            boolean changed, boolean blocked) {
        return policy.decide(assessments, CURRENT_LEG, NOW, previousInterval, changed, blocked);
    }

    @Nested
    @DisplayName("#480 환승 아닌 대기(첫 승차)가 남으면 BOARDING — 기본 간격, 비긴급, 예측이 지나기 전에 다시")
    class WaitAhead {

        private JourneyEvaluationIntervalPolicy.WaitsAhead waits(Instant earliest) {
            return new JourneyEvaluationIntervalPolicy.WaitsAhead(true, earliest);
        }

        @Test
        @DisplayName("환승이 없고 첫 승차가 남았으면 기본 간격 30초·비긴급·주의 등급 — 한가함(2분)으로 두지 않는다")
        void plainWaitIsBoardingInterval() {
            Decision decision = policy.decide(List.of(), CURRENT_LEG, NOW, null, false, false,
                    waits(NOW.plusSeconds(600)));

            assertThat(decision.reason()).isEqualTo(Reason.BOARDING);
            assertThat(decision.interval()).isEqualTo(Duration.ofSeconds(30));
            assertThat(decision.urgent()).isFalse();
            assertThat(policy.tierOf(List.of(), CURRENT_LEG, NOW, false, waits(NOW.plusSeconds(600))))
                    .isEqualTo(watoo.grd.nextroute.application.journey.reeval.ReevaluationTier.CAUTION);
        }

        @Test
        @DisplayName("예측이 간격보다 일찍 지나면 그 전에 다시 본다 — 최소 간격(10초) 아래로는 아니다")
        void capsBeforePredictionPasses() {
            assertThat(policy.decide(List.of(), CURRENT_LEG, NOW, null, false, false,
                    waits(NOW.plusSeconds(20))).interval()).isEqualTo(Duration.ofSeconds(20));
            assertThat(policy.decide(List.of(), CURRENT_LEG, NOW, null, false, false,
                    waits(NOW.plusSeconds(3))).interval()).isEqualTo(Duration.ofSeconds(10));
        }

        @Test
        @DisplayName("값이 그대로면 늘리되 예측이 지나기 전으로 자른다. 예측을 모르면 자르지 않는다")
        void backoffIsCappedByPrediction() {
            Decision capped = policy.decide(List.of(), CURRENT_LEG, NOW, Duration.ofSeconds(30), false, false,
                    waits(NOW.plusSeconds(45)));
            assertThat(capped.interval()).isEqualTo(Duration.ofSeconds(45));
            assertThat(capped.reason()).isEqualTo(Reason.BOARDING);

            Decision unknown = policy.decide(List.of(), CURRENT_LEG, NOW, Duration.ofSeconds(30), false, false,
                    waits(null));
            assertThat(unknown.reason()).isEqualTo(Reason.BACKOFF);
        }

        @Test
        @DisplayName("긴급 결정(위험·임박)은 예측 시각으로 자르지 않는다 — 긴급 줄에 남는다")
        void urgentDecisionIsNotCapped() {
            Decision atRisk = policy.decide(List.of(transfer(TransferStatus.AT_RISK, Duration.ofMinutes(10))),
                    CURRENT_LEG, NOW, null, false, false, waits(NOW.plusSeconds(3)));

            assertThat(atRisk.reason()).isEqualTo(Reason.AT_RISK);
            assertThat(atRisk.urgent()).isTrue();
        }

        @Test
        @DisplayName("환승이 임박하면 환승 규칙이 이긴다(10초·긴급). 대기가 없으면 예전처럼 한가함")
        void transferRuleWinsAndNoneIsIdle() {
            Decision near = policy.decide(List.of(transfer(TransferStatus.COMFORTABLE, Duration.ofMinutes(2))),
                    CURRENT_LEG, NOW, null, false, false, waits(NOW.plusSeconds(600)));
            assertThat(near.reason()).isEqualTo(Reason.NEAR);
            assertThat(near.urgent()).isTrue();

            assertThat(policy.decide(List.of(), CURRENT_LEG, NOW, null, false, false,
                    JourneyEvaluationIntervalPolicy.WaitsAhead.NONE).reason()).isEqualTo(Reason.IDLE);
        }
    }

    @Nested
    @DisplayName("급한 정도로 정한다")
    class ByUrgency {

        @Test
        void TC_막히면_기본의_네_배() {
            Decision decision = decide(List.of(transfer(TransferStatus.AT_RISK, Duration.ofMinutes(2))),
                    null, true, true);

            // 막힘이 다른 모든 규칙을 이긴다. 막힌 문을 10초마다 두드리지 않는다.
            assertThat(decision.reason()).isEqualTo(Reason.BLOCKED);
            assertThat(decision.interval()).isEqualTo(Duration.ofSeconds(120));
        }

        @Test
        void TC_놓칠_위험이_있으면_최소_간격() {
            Decision decision = decide(List.of(transfer(TransferStatus.AT_RISK, Duration.ofMinutes(30))),
                    null, true, false);

            // 환승까지 30분이 남았어도 위험 판정이면 자주 본다.
            assertThat(decision.reason()).isEqualTo(Reason.AT_RISK);
            assertThat(decision.interval()).isEqualTo(Duration.ofSeconds(10));
        }

        @Test
        void TC_이미_놓쳤어도_최소_간격() {
            Decision decision = decide(List.of(transfer(TransferStatus.MISSED, Duration.ofMinutes(3))),
                    null, true, false);

            assertThat(decision.reason()).isEqualTo(Reason.AT_RISK);
        }

        @Test
        void TC_환승이_오분_안이면_최소_간격() {
            Decision decision = decide(
                    List.of(transfer(TransferStatus.COMFORTABLE, Duration.ofMinutes(4))),
                    null, true, false);

            assertThat(decision.reason()).isEqualTo(Reason.NEAR);
            assertThat(decision.interval()).isEqualTo(Duration.ofSeconds(10));
        }

        @Test
        void TC_십분_남았으면_기본_간격() {
            Decision decision = decide(
                    List.of(transfer(TransferStatus.COMFORTABLE, Duration.ofMinutes(10))),
                    null, true, false);

            assertThat(decision.reason()).isEqualTo(Reason.NORMAL);
            assertThat(decision.interval()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        void TC_이십분_남았으면_한가한_간격() {
            Decision decision = decide(
                    List.of(transfer(TransferStatus.COMFORTABLE, Duration.ofMinutes(20))),
                    null, true, false);

            assertThat(decision.reason()).isEqualTo(Reason.IDLE);
            assertThat(decision.interval()).isEqualTo(Duration.ofSeconds(120));
        }

        @Test
        void TC_환승이_없으면_한가한_간격() {
            Decision decision = decide(List.of(), null, true, false);

            assertThat(decision.reason()).isEqualTo(Reason.IDLE);
        }

        @Test
        void TC_가장_가까운_환승을_기준으로_한다() {
            Decision decision = decide(List.of(
                    assessment(TransferStatus.COMFORTABLE, Duration.ofMinutes(3), CURRENT_LEG + 1),
                    assessment(TransferStatus.COMFORTABLE, Duration.ofMinutes(40), CURRENT_LEG + 3)),
                    null, true, false);

            assertThat(decision.reason()).isEqualTo(Reason.NEAR);
        }

        @Test
        void TC_지나온_환승은_세지_않는다() {
            // 이미 갈아탄 환승의 준비 시각은 과거다. 그것까지 보면 여정 후반이 계속 10초로 돈다.
            Decision decision = decide(List.of(
                    assessment(TransferStatus.COMFORTABLE, Duration.ofMinutes(-20), CURRENT_LEG - 1),
                    assessment(TransferStatus.COMFORTABLE, Duration.ofMinutes(30), CURRENT_LEG + 2)),
                    null, true, false);

            assertThat(decision.reason()).isEqualTo(Reason.IDLE);
        }

        @Test
        void TC_지나온_환승의_위험_판정도_무시한다() {
            Decision decision = decide(List.of(
                    assessment(TransferStatus.MISSED, Duration.ofMinutes(-10), CURRENT_LEG - 1)),
                    null, true, false);

            assertThat(decision.reason()).isEqualTo(Reason.IDLE);
        }
    }

    @Nested
    @DisplayName("값이 그대로면 간격을 늘린다")
    class Backoff {

        @Test
        void TC_안_바뀌면_직전_간격의_두_배() {
            Decision decision = decide(
                    List.of(transfer(TransferStatus.COMFORTABLE, Duration.ofMinutes(10))),
                    Duration.ofSeconds(30), false, false);

            assertThat(decision.reason()).isEqualTo(Reason.BACKOFF);
            assertThat(decision.interval()).isEqualTo(Duration.ofSeconds(60));
        }

        @Test
        void TC_계속_안_바뀌면_계속_늘어나고_상한에서_멈춘다() {
            List<TransferRiskAssessment> quiet =
                    List.of(transfer(TransferStatus.COMFORTABLE, Duration.ofMinutes(10)));

            assertThat(decide(quiet, Duration.ofSeconds(60), false, false).interval())
                    .isEqualTo(Duration.ofSeconds(120));
            assertThat(decide(quiet, Duration.ofSeconds(120), false, false).interval())
                    .isEqualTo(Duration.ofSeconds(180));
            assertThat(decide(quiet, Duration.ofSeconds(180), false, false).interval())
                    .isEqualTo(Duration.ofSeconds(180));
        }

        @Test
        void TC_바뀌었으면_규칙_값으로_돌아온다() {
            Decision decision = decide(
                    List.of(transfer(TransferStatus.COMFORTABLE, Duration.ofMinutes(10))),
                    Duration.ofSeconds(180), true, false);

            assertThat(decision.reason()).isEqualTo(Reason.NORMAL);
            assertThat(decision.interval()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        void TC_급한_상태에서는_늘리지_않는다() {
            // 값이 그대로여도 위험한 상황이면 자주 봐야 한다. 뜸해지면 알림이 늦는다.
            Decision atRisk = decide(
                    List.of(transfer(TransferStatus.AT_RISK, Duration.ofMinutes(2))),
                    Duration.ofSeconds(120), false, false);
            Decision near = decide(
                    List.of(transfer(TransferStatus.COMFORTABLE, Duration.ofMinutes(3))),
                    Duration.ofSeconds(120), false, false);

            assertThat(atRisk.interval()).isEqualTo(Duration.ofSeconds(10));
            assertThat(near.interval()).isEqualTo(Duration.ofSeconds(10));
        }

        @Test
        void TC_직전_간격을_모르면_규칙_값을_쓴다() {
            Decision decision = decide(
                    List.of(transfer(TransferStatus.COMFORTABLE, Duration.ofMinutes(10))),
                    null, false, false);

            assertThat(decision.reason()).isEqualTo(Reason.NORMAL);
            assertThat(decision.interval()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        void TC_직전_간격이_터무니없이_길어도_상한을_넘지_않는다() {
            // 워커가 한참 멈췄다 돌아온 경우. 그대로 곱하면 몇 시간짜리 간격이 나온다.
            Decision decision = decide(
                    List.of(transfer(TransferStatus.COMFORTABLE, Duration.ofMinutes(10))),
                    Duration.ofHours(2), false, false);

            assertThat(decision.interval()).isEqualTo(Duration.ofSeconds(180));
        }

        @Test
        void TC_직전_간격이_규칙_값보다_짧으면_규칙_값을_쓴다() {
            // 직전엔 급해서 10초였고 지금은 한가하다. 20초로 되돌아가지 않는다.
            Decision decision = decide(List.of(), Duration.ofSeconds(10), false, false);

            assertThat(decision.interval()).isEqualTo(Duration.ofSeconds(120));
            assertThat(decision.reason()).isEqualTo(Reason.IDLE);
        }
    }

    @Test
    void TC_결과는_항상_최소와_최대_사이() {
        JourneyEvaluatorProperties tight = new JourneyEvaluatorProperties();
        tight.setMinIntervalSeconds(20);
        tight.setMaxIntervalSeconds(40);
        tight.setIdleIntervalSeconds(40);
        JourneyEvaluationIntervalPolicy tightPolicy = new JourneyEvaluationIntervalPolicy(tight);

        List<Decision> decisions = List.of(
                tightPolicy.decide(List.of(transfer(TransferStatus.AT_RISK, Duration.ofMinutes(1))),
                        CURRENT_LEG, NOW, null, true, false),
                tightPolicy.decide(List.of(), CURRENT_LEG, NOW, Duration.ofMinutes(30), false, false),
                tightPolicy.decide(List.of(), CURRENT_LEG, NOW, null, true, true));

        assertThat(decisions).allSatisfy(decision -> assertThat(decision.interval())
                .isBetween(Duration.ofSeconds(20), Duration.ofSeconds(40)));
    }

    @Nested
    @DisplayName("재평가 등급(#415-3)")
    class ReevaluationTierOf {

        @Test
        @DisplayName("위험이 있으면 멀어도 긴급")
        void riskIsUrgent() {
            assertThat(policy.tierOf(List.of(transfer(TransferStatus.AT_RISK, Duration.ofMinutes(30))),
                    CURRENT_LEG, NOW, false)).isEqualTo(ReevaluationTier.URGENT);
        }

        @Test
        @DisplayName("5분 이내는 긴급, 15분 이내는 주의, 그 밖은 여유")
        void byTimeToTransfer() {
            assertThat(policy.tierOf(List.of(transfer(TransferStatus.COMFORTABLE, Duration.ofMinutes(4))),
                    CURRENT_LEG, NOW, false)).isEqualTo(ReevaluationTier.URGENT);
            assertThat(policy.tierOf(List.of(transfer(TransferStatus.COMFORTABLE, Duration.ofMinutes(10))),
                    CURRENT_LEG, NOW, false)).isEqualTo(ReevaluationTier.CAUTION);
            assertThat(policy.tierOf(List.of(transfer(TransferStatus.COMFORTABLE, Duration.ofMinutes(30))),
                    CURRENT_LEG, NOW, false)).isEqualTo(ReevaluationTier.RELAXED);
        }

        @Test
        @DisplayName("값이 그대로여서 간격이 늘어나도(BACKOFF) 등급은 급한 정도대로다")
        void backoffDoesNotHideCaution() {
            List<TransferRiskAssessment> caution = List.of(transfer(TransferStatus.COMFORTABLE, Duration.ofMinutes(10)));
            assertThat(decide(caution, Duration.ofSeconds(60), false, false).reason())
                    .isEqualTo(JourneyEvaluationIntervalPolicy.Reason.BACKOFF);
            assertThat(policy.tierOf(caution, CURRENT_LEG, NOW, false)).isEqualTo(ReevaluationTier.CAUTION);
        }

        @Test
        @DisplayName("provider 가 막혔으면 급해도 여유 — 막힌 문을 곧바로 다시 두드리지 않는다")
        void blockedProviderIsRelaxed() {
            assertThat(policy.tierOf(List.of(transfer(TransferStatus.AT_RISK, Duration.ofMinutes(2))),
                    CURRENT_LEG, NOW, true)).isEqualTo(ReevaluationTier.RELAXED);
        }

        @Test
        @DisplayName("지나온 환승은 보지 않는다")
        void ignoresPassedTransfers() {
            assertThat(policy.tierOf(List.of(assessment(TransferStatus.AT_RISK, Duration.ofMinutes(1), CURRENT_LEG - 1)),
                    CURRENT_LEG, NOW, false)).isEqualTo(ReevaluationTier.RELAXED);
        }
    }
}
