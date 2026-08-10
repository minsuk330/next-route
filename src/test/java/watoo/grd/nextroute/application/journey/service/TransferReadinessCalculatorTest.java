package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyTransferRiskProperties;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("사용자가 환승 정류장에서 탈 준비를 마치는 시각")
class TransferReadinessCalculatorTest {

    private static final Instant ARRIVED = Instant.parse("2026-08-04T09:00:00Z");

    // 기본 하차 버퍼 30초
    private final TransferReadinessCalculator calculator =
            new TransferReadinessCalculator(new JourneyTransferRiskProperties());

    @Test
    @DisplayName("도착 시각에 하차 버퍼와 환승 도보를 더한다")
    void addsBufferAndWalk() {
        Instant ready = calculator.userReadyAt(ARRIVED, 120, TransferReadinessCalculator.Progress.RIDING, null);

        // 09:00:00 + 30초 + 120초
        assertThat(ready).isEqualTo(Instant.parse("2026-08-04T09:02:30Z"));
    }

    @Test
    @DisplayName("같은 정류장 환승이면 도보 없이 하차 버퍼만 더한다")
    void sameStopAddsBufferOnly() {
        assertThat(calculator.userReadyAt(ARRIVED, null, TransferReadinessCalculator.Progress.RIDING, null))
                .isEqualTo(Instant.parse("2026-08-04T09:00:30Z"));
        assertThat(calculator.userReadyAt(ARRIVED, 0, TransferReadinessCalculator.Progress.RIDING, null))
                .isEqualTo(Instant.parse("2026-08-04T09:00:30Z"));
    }

    @Test
    @DisplayName("도보 시간이 음수로 들어와도 여유를 늘리지 않는다")
    void negativeWalkIsClamped() {
        assertThat(calculator.userReadyAt(ARRIVED, -300, TransferReadinessCalculator.Progress.RIDING, null))
                .isEqualTo(Instant.parse("2026-08-04T09:00:30Z"));
    }

    @Test
    @DisplayName("현재 leg 도착 시각을 모르면 준비 시각도 모른다")
    void unknownArrivalMeansUnknownReady() {
        assertThat(calculator.userReadyAt(null, 120, TransferReadinessCalculator.Progress.RIDING, null)).isNull();
    }

    @Test
    @DisplayName("이미 정류장에 서 있으면 지금이 준비 완료 시각이다")
    void waitingIsReadyNow() {
        Instant now = Instant.parse("2026-08-04T09:05:00Z");

        assertThat(calculator.userReadyAt(ARRIVED, 120,
                TransferReadinessCalculator.Progress.WAITING, now))
                .as("이미 걸어와 서 있는 사람에게 하차 버퍼와 도보를 다시 더하면, 곧 오는 차가 "
                        + "이미 지나간 차로 판정된다")
                .isEqualTo(now);
    }

    @Test
    @DisplayName("걷는 중이면 지금부터 남은 도보만 더한다 — 하차는 이미 끝났다")
    void walkingAddsWalkOnly() {
        Instant now = Instant.parse("2026-08-04T09:05:00Z");

        assertThat(calculator.userReadyAt(ARRIVED, 120,
                TransferReadinessCalculator.Progress.WALKING, now))
                .isEqualTo(now.plusSeconds(120));
    }

    @Test
    @DisplayName("여유는 다음 차량 도착에서 준비 완료를 뺀 값이다")
    void slackIsDifference() {
        Instant ready = Instant.parse("2026-08-04T09:02:30Z");

        assertThat(calculator.transferSlackSeconds(ready, ready.plusSeconds(75))).isEqualTo(75);
        assertThat(calculator.transferSlackSeconds(ready, ready.minusSeconds(40))).isEqualTo(-40);
    }

    @Test
    @DisplayName("한쪽이라도 모르면 여유를 만들지 않는다")
    void missingSideMeansNullSlack() {
        Instant ready = Instant.parse("2026-08-04T09:02:30Z");

        assertThat(calculator.transferSlackSeconds(null, ready)).isNull();
        assertThat(calculator.transferSlackSeconds(ready, null)).isNull();
    }
}
