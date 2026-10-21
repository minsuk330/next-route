package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.dto.JourneyEvaluationOutcome;
import watoo.grd.nextroute.application.journey.dto.TransferAssessmentBatch;
import watoo.grd.nextroute.application.journey.dto.TransferTargetArrival;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 대기 단계 도착 예측 저장(#480). 한 번 못 구했다고 쓸 만한 직전 예측을 지우지 않는다 — 지하철 원천이 열차를 한 tick
 * 빼먹으면 도착 예정이 "시간 확인 중"으로 깜박였다. 오래 못 구하면 지운다.
 */
@DisplayName("평가 저장 — 대기 단계 도착 예측")
class JourneyEvaluationScheduleWriterBoardingTest {

    private static final Long JOURNEY_ID = 9L;
    private static final int REVISION = 1;
    private static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");

    private final JourneySessionRepository sessionRepository = mock(JourneySessionRepository.class);
    private final JourneyLegRepository legRepository = mock(JourneyLegRepository.class);
    private final TransferRiskNotifier riskNotifier = mock(TransferRiskNotifier.class);
    private final TransferTargetApproachNotifier approachNotifier = mock(TransferTargetApproachNotifier.class);
    private final JourneyEvaluationScheduleWriter writer = new JourneyEvaluationScheduleWriter(sessionRepository,
            legRepository, mock(TransferAssessmentWriter.class), riskNotifier, approachNotifier,
            mock(ApplicationEventPublisher.class));

    @Test
    @DisplayName("이번에 못 구했어도 직전 예측이 앞날이고 90초 안에 본 것이면 그대로 둔다")
    void unknownKeepsRecentPrediction() {
        JourneyLeg wait = waitLeg(NOW.plusSeconds(120), NOW.minusSeconds(30));

        finish(wait, TransferTargetArrival.unknown());

        assertThat(wait.getPredictedEndAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(wait.getEvidenceObservedAt()).isEqualTo(NOW.minusSeconds(30));
    }

    @Test
    @DisplayName("직전 예측을 본 지 90초가 넘었거나 이미 지난 시각이면 지운다 — 탈 수 없게 된 열차를 붙잡지 않는다")
    void unknownClearsOldOrPastPrediction() {
        JourneyLeg old = waitLeg(NOW.plusSeconds(120), NOW.minusSeconds(200));
        finish(old, TransferTargetArrival.unknown());
        assertThat(old.getPredictedEndAt()).isNull();

        JourneyLeg past = waitLeg(NOW.minusSeconds(5), NOW.minusSeconds(30));
        finish(past, TransferTargetArrival.unknown());
        assertThat(past.getPredictedEndAt()).isNull();
    }

    @Test
    @DisplayName("새로 구했으면 언제나 새 값이다")
    void knownAlwaysOverwrites() {
        JourneyLeg wait = waitLeg(NOW.plusSeconds(120), NOW.minusSeconds(30));
        CurrentLegArrival fresh = new CurrentLegArrival(NOW.plusSeconds(200), 200, null, null,
                CurrentLegArrival.Source.REALTIME_ARRIVAL, CurrentLegArrival.Quality.APPROXIMATE, NOW);

        finish(wait, TransferTargetArrival.of(fresh));

        assertThat(wait.getPredictedEndAt()).isEqualTo(NOW.plusSeconds(200));
        assertThat(wait.getEvidenceObservedAt()).isEqualTo(NOW);
    }

    private void finish(JourneyLeg wait, TransferTargetArrival target) {
        given(sessionRepository.finishEvaluation(eq(JOURNEY_ID), any(), any(), anyBoolean(), anyBoolean(), any(),
                anyLong())).willReturn(1);
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(JOURNEY_ID, REVISION))
                .willReturn(List.of(wait));
        JourneyEvaluationOutcome outcome = new JourneyEvaluationOutcome(JOURNEY_ID, REVISION,
                new TransferAssessmentBatch(List.of(), Map.of(wait.getLegIndex(), target), false), false);

        assertThat(writer.finish(new JourneyDueClaim(JOURNEY_ID, NOW.plusSeconds(60), NOW), outcome,
                NOW.plusSeconds(30), false, 0L, NOW, null)).isTrue();
    }

    private static JourneyLeg waitLeg(Instant predictedEndAt, Instant observedAt) {
        JourneyLeg leg = JourneyLeg.builder().journeyId(JOURNEY_ID).routeRevision(REVISION).legIndex(1)
                .type(JourneyLegType.WAIT_SUBWAY).state(JourneyLegState.ACTIVE).build();
        ReflectionTestUtils.setField(leg, "id", 101L);
        leg.applyBoardingPrediction(predictedEndAt, "REALTIME_ARRIVAL", "GOOD", observedAt);
        return leg;
    }
}
