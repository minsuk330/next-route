package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("갈아탈 차가 곧 온다고 알릴 조건")
class TransferTargetApproachPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-10T09:00:00Z");
    private static final int WAIT_LEG = 3;

    // 기본값: window 120초
    private final TransferTargetApproachPolicy policy =
            new TransferTargetApproachPolicy(new JourneyNotificationProperties());

    @Nested
    @DisplayName("남은 시간")
    class Window {

        @ParameterizedTest(name = "도착까지 {0}초 → {1}")
        @CsvSource({
                "-1, false",    // 이미 지나갔다
                "0, false",     // 지금 도착. 알려도 늦었다
                "1, true",
                "60, true",
                "120, true",    // 창의 오른쪽은 닫혀 있다
                "121, false"
        })
        @DisplayName("창은 왼쪽이 열리고 오른쪽이 닫힌다")
        void checksWindowBoundaries(long secondsUntilArrival, boolean expected) {
            TransferRiskAssessment assessment =
                    assessment(TransferStatus.CATCHABLE, NOW.plusSeconds(secondsUntilArrival));

            assertThat(policy.isApproaching(assessment, WAIT_LEG, NOW)).isEqualTo(expected);
        }

        @Test
        @DisplayName("다음 차 도착 시각을 모르면 알리지 않는다")
        void skipsWhenArrivalUnknown() {
            TransferRiskAssessment assessment = assessment(TransferStatus.CATCHABLE, null);

            assertThat(policy.isApproaching(assessment, WAIT_LEG, NOW)).isFalse();
        }
    }

    @Nested
    @DisplayName("환승 상태")
    class Status {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = TransferStatus.class, names = {"COMFORTABLE", "CATCHABLE", "AT_RISK"})
        @DisplayName("아직 놓치지 않았으면 알린다 — 정류장에 서 있으면 AT_RISK 가 정상값이다")
        void notifiesUnlessMissed(TransferStatus status) {
            TransferRiskAssessment assessment = assessment(status, NOW.plusSeconds(60));

            assertThat(policy.isApproaching(assessment, WAIT_LEG, NOW)).isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = TransferStatus.class, names = {"MISSED", "UNKNOWN"})
        @DisplayName("이미 놓쳤거나 근거가 없으면 알리지 않는다 — 지나간 차를 곧 온다고 할 수 없다")
        void skipsWhenMissedOrUnknown(TransferStatus status) {
            TransferRiskAssessment assessment = assessment(status, NOW.plusSeconds(60));

            assertThat(policy.isApproaching(assessment, WAIT_LEG, NOW)).isFalse();
        }
    }

    @Nested
    @DisplayName("현재 단계")
    class LegPosition {

        @Test
        @DisplayName("그 환승의 대기 단계에 들어와 있을 때만 알린다")
        void notifiesOnlyWhileWaiting() {
            TransferRiskAssessment assessment =
                    assessment(TransferStatus.CATCHABLE, NOW.plusSeconds(60));

            assertThat(policy.isApproaching(assessment, WAIT_LEG, NOW)).isTrue();
        }

        @Test
        @DisplayName("아직 앞 차를 타고 있으면 알리지 않는다 — 할 수 있는 일이 없다")
        void skipsWhileStillOnPreviousLeg() {
            TransferRiskAssessment assessment =
                    assessment(TransferStatus.CATCHABLE, NOW.plusSeconds(60));

            assertThat(policy.isApproaching(assessment, WAIT_LEG - 1, NOW)).isFalse();
        }

        @Test
        @DisplayName("이미 지나온 환승은 알리지 않는다")
        void skipsPastTransfer() {
            TransferRiskAssessment assessment =
                    assessment(TransferStatus.CATCHABLE, NOW.plusSeconds(60));

            assertThat(policy.isApproaching(assessment, WAIT_LEG + 1, NOW)).isFalse();
        }
    }

    @Test
    @DisplayName("스위치를 끄면 아무것도 알리지 않는다")
    void respectsKillSwitch() {
        JourneyNotificationProperties properties = new JourneyNotificationProperties();
        properties.getApproach().setEnabled(false);
        TransferTargetApproachPolicy off = new TransferTargetApproachPolicy(properties);

        assertThat(off.isApproaching(assessment(TransferStatus.CATCHABLE, NOW.plusSeconds(60)),
                WAIT_LEG, NOW)).isFalse();
    }

    private TransferRiskAssessment assessment(TransferStatus status, Instant nextBusArrivalAt) {
        return new TransferRiskAssessment(0, WAIT_LEG - 1, WAIT_LEG, status,
                NOW, NOW, nextBusArrivalAt, 300L,
                CurrentLegArrival.Quality.GOOD, NOW, CurrentLegArrival.Source.REALTIME_ARRIVAL, null);
    }
}
