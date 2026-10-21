package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationContextLoader.JourneyEvaluationContext;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationContextLoader.StoredBoarding;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 직전 평가 간격 — 백오프의 기준(#480). 판정이 있으면 판정 시각, 없으면 대기 예측 근거 시각. */
@DisplayName("평가 문맥 — 직전 평가 간격")
class JourneyEvaluationContextIntervalTest {

    private static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");

    @Test
    @DisplayName("환승 없는 여정은 대기 예측 근거 시각으로 직전 평가를 읽는다 — 첫 차를 기다리는 동안 백오프가 된다")
    void withoutAssessmentsUsesBoardingObservedAt() {
        JourneyEvaluationContext context = new JourneyEvaluationContext(1L, 2L, 1, 0, Map.of(), false,
                Map.of(0, new StoredBoarding(NOW.plusSeconds(600), NOW.minusSeconds(30))));

        assertThat(context.previousInterval(NOW)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("판정이 있으면 판정 시각이 기준이다 — 늘 최근인 대기 근거 시각을 섞지 않는다")
    void assessmentsWinOverBoardings() {
        TransferRiskAssessment assessment = new TransferRiskAssessment(0, 1, 3, TransferStatus.UNKNOWN,
                null, null, null, null, CurrentLegArrival.Quality.UNKNOWN, NOW.minusSeconds(60), null, null);
        JourneyEvaluationContext context = new JourneyEvaluationContext(1L, 2L, 1, 0, Map.of(0, assessment), true,
                Map.of(3, new StoredBoarding(NOW.plusSeconds(600), NOW.minusSeconds(2))));

        assertThat(context.previousInterval(NOW)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("둘 다 없으면 모름")
    void noneIsNull() {
        assertThat(new JourneyEvaluationContext(1L, 2L, 1, 0, Map.of(), false).previousInterval(NOW)).isNull();
    }
}
