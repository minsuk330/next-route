package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import watoo.grd.nextroute.application.journey.config.JourneyTransferRiskProperties;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("환승 여유를 다섯 단계 상태로 옮긴다")
class TransferRiskPolicyTest {

    // 기본값: comfortable 300, catchable 120, hysteresis 60
    private final TransferRiskPolicy policy =
            new TransferRiskPolicy(new JourneyTransferRiskProperties());

    @Nested
    @DisplayName("여유만 볼 때")
    class Classify {

        @ParameterizedTest(name = "{0}초 → {1}")
        @CsvSource({
                "-1, MISSED",
                "-600, MISSED",
                "0, AT_RISK",
                "119, AT_RISK",
                "120, CATCHABLE",
                "299, CATCHABLE",
                "300, COMFORTABLE",
                "3600, COMFORTABLE"
        })
        @DisplayName("경계값이 임계와 정확히 맞는다")
        void classifiesByThreshold(long slack, TransferStatus expected) {
            assertThat(policy.classify(slack)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("직전 상태가 있을 때")
    class WithPrevious {

        @Test
        @DisplayName("나빠지는 방향은 유예 없이 바로 바뀐다")
        void worseAppliesImmediately() {
            // 여유 119초는 AT_RISK 구간. 유예를 걸면 사용자가 늦게 알아 놓친다.
            assertThat(policy.evaluate(119L, TransferStatus.COMFORTABLE))
                    .isEqualTo(TransferStatus.AT_RISK);
            assertThat(policy.evaluate(-5L, TransferStatus.CATCHABLE))
                    .isEqualTo(TransferStatus.MISSED);
        }

        @Test
        @DisplayName("좋아지는 방향은 임계만 넘겨서는 부족하다")
        void betterNeedsHysteresis() {
            // 120초면 CATCHABLE 구간이지만 임계+60 = 180 미만이라 AT_RISK 를 유지한다.
            assertThat(policy.evaluate(120L, TransferStatus.AT_RISK))
                    .isEqualTo(TransferStatus.AT_RISK);
            assertThat(policy.evaluate(179L, TransferStatus.AT_RISK))
                    .isEqualTo(TransferStatus.AT_RISK);
        }

        @Test
        @DisplayName("여유를 충분히 확보하면 좋아진다")
        void betterAppliesBeyondHysteresis() {
            assertThat(policy.evaluate(180L, TransferStatus.AT_RISK))
                    .isEqualTo(TransferStatus.CATCHABLE);
            assertThat(policy.evaluate(360L, TransferStatus.CATCHABLE))
                    .isEqualTo(TransferStatus.COMFORTABLE);
        }

        @Test
        @DisplayName("임계 근처에서 흔들려도 상태가 오르내리지 않는다")
        void doesNotFlapAroundThreshold() {
            TransferStatus state = policy.evaluate(400L, null);
            assertThat(state).isEqualTo(TransferStatus.COMFORTABLE);

            // 295 → 305 → 295 로 흔들린다. 한 번 내려가면 305 로는 못 올라온다(360 필요).
            state = policy.evaluate(295L, state);
            assertThat(state).isEqualTo(TransferStatus.CATCHABLE);
            state = policy.evaluate(305L, state);
            assertThat(state).isEqualTo(TransferStatus.CATCHABLE);
            state = policy.evaluate(295L, state);
            assertThat(state).isEqualTo(TransferStatus.CATCHABLE);
        }

        @Test
        @DisplayName("놓친 상태에서도 여유가 충분해지면 회복한다")
        void recoversFromMissed() {
            assertThat(policy.evaluate(500L, TransferStatus.MISSED))
                    .isEqualTo(TransferStatus.COMFORTABLE);
        }

        @Test
        @DisplayName("직전이 모름이면 유예 없이 지금 여유대로 매긴다")
        void previousUnknownAppliesRaw() {
            assertThat(policy.evaluate(130L, TransferStatus.UNKNOWN))
                    .isEqualTo(TransferStatus.CATCHABLE);
        }
    }

    @Test
    @DisplayName("여유를 모르면 그럴듯한 상태를 만들지 않는다")
    void nullSlackIsUnknown() {
        assertThat(policy.evaluate(null, TransferStatus.COMFORTABLE))
                .isEqualTo(TransferStatus.UNKNOWN);
        assertThat(policy.evaluate(null, null)).isEqualTo(TransferStatus.UNKNOWN);
    }

    @Test
    @DisplayName("모름은 놓침보다 나쁘지 않다 — 모른다고 포기시키지 않는다")
    void unknownIsNotWorseThanMissed() {
        // 모름 상태였다가 여유가 음수로 확인되면 놓침으로 내려간다.
        assertThat(policy.evaluate(-1L, TransferStatus.UNKNOWN)).isEqualTo(TransferStatus.MISSED);
        // 반대로 놓침이었다가 여유를 모르게 되면 놓침을 유지하지 않고 모름으로 간다.
        assertThat(policy.evaluate(null, TransferStatus.MISSED)).isEqualTo(TransferStatus.UNKNOWN);
    }
}
