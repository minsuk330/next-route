package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyEvaluationOutcome;
import watoo.grd.nextroute.application.journey.dto.LaneReselection;
import watoo.grd.nextroute.application.journey.dto.TransferAssessmentBatch;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.dto.TransferTargetArrival;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;
import watoo.grd.nextroute.domain.journey.entity.RouteOptionSelectedBy;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("#614 PR-2 평가 저장 — 노선 옮기기")
class JourneyEvaluationScheduleWriterLaneReselectTest {

    private static final Long JOURNEY_ID = 9L;
    private static final int REVISION = 1;
    private static final Instant NOW = Instant.parse("2026-11-17T00:40:00Z");

    private final JourneyLegRepository legRepository = mock(JourneyLegRepository.class);
    private final JourneyNotificationRepository notificationRepository = mock(JourneyNotificationRepository.class);
    private final JourneyEvaluationScheduleWriter writer = new JourneyEvaluationScheduleWriter(
            mock(JourneySessionRepository.class), legRepository, mock(TransferAssessmentWriter.class),
            mock(TransferRiskNotifier.class), mock(TransferTargetApproachNotifier.class),
            mock(ApplicationEventPublisher.class), notificationRepository);

    private final LaneReselection reselection = new LaneReselection(3, 4, 11L, 13L, "R-140", "140", 905, 1140, 180);

    private static JourneyLeg leg(int index, JourneyLegType type, JourneyLegState state) {
        JourneyLeg leg = JourneyLeg.builder().journeyId(JOURNEY_ID).routeRevision(REVISION).legIndex(index)
                .type(type).state(state).plannedDurationSec(700).build();
        ReflectionTestUtils.setField(leg, "id", 100L + index);
        leg.selectRouteOption(11L, "R-402", RouteOptionSelectedBy.DEFAULT);
        return leg;
    }

    private static TransferRiskAssessment assessmentInto(int transferIndex, int toLegIndex) {
        return new TransferRiskAssessment(transferIndex, 1, toLegIndex, TransferStatus.CATCHABLE, NOW, NOW,
                NOW.plusSeconds(180), 120L, CurrentLegArrival.Quality.GOOD, NOW, CurrentLegArrival.Source.REALTIME_ARRIVAL,
                null);
    }

    private JourneyEvaluationOutcome outcome() {
        TransferTargetArrival boarding = TransferTargetArrival.of(new CurrentLegArrival(NOW.plusSeconds(180), 180,
                null, null, CurrentLegArrival.Source.REALTIME_ARRIVAL, CurrentLegArrival.Quality.GOOD, NOW));
        TransferAssessmentBatch batch = new TransferAssessmentBatch(
                List.of(assessmentInto(0, 3), assessmentInto(1, 6)),
                Map.of(3, boarding, 6, boarding), false, reselection);
        return new JourneyEvaluationOutcome(JOURNEY_ID, REVISION, batch, true);
    }

    @Test
    @DisplayName("대기 중·선택 그대로·DEFAULT 면 대기·탑승을 옮기고 탑승 소요를 바꾸고, 그 환승의 대기 중 접근 알림을 취소한다")
    void appliesAndCancelsApproach() {
        JourneyLeg wait = leg(3, JourneyLegType.WAIT_BUS, JourneyLegState.ACTIVE);
        JourneyLeg ride = leg(4, JourneyLegType.RIDE_BUS, JourneyLegState.PENDING);
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(JOURNEY_ID, REVISION))
                .willReturn(List.of(wait, ride));

        JourneyEvaluationOutcome applied = writer.applyLaneReselection(outcome());

        assertThat(wait.getSelectedRouteOptionId()).isEqualTo(13L);
        assertThat(wait.getRouteId()).isEqualTo("R-140");
        assertThat(ride.getSelectedRouteOptionId()).isEqualTo(13L);
        assertThat(ride.getPlannedDurationSec()).isEqualTo(905);
        assertThat(wait.getRouteOptionSelectedByOrDefault()).isEqualTo(RouteOptionSelectedBy.DEFAULT);
        assertThat(applied.batch().boardingByLegIndex()).containsKey(3);
        verify(notificationRepository).supersedeAllPendingByScope(JOURNEY_ID, REVISION, JourneyNotificationScope.TRANSFER,
                0, List.of(JourneyCheckpoint.TRANSFER_TARGET_APPROACHING));
        verify(notificationRepository, never()).supersedeAllPendingByScope(anyLong(), anyInt(), any(), org.mockito.ArgumentMatchers.eq(1), any());
    }

    @Test
    @DisplayName("저장 직전 사용자가 골랐으면(USER) 옮기지 않고 그 대기 예측·판정만 버린다 — 나머지는 저장")
    void userChoseInBetween() {
        JourneyLeg wait = leg(3, JourneyLegType.WAIT_BUS, JourneyLegState.ACTIVE);
        wait.markRouteOptionSelectedBy(RouteOptionSelectedBy.USER);
        JourneyLeg ride = leg(4, JourneyLegType.RIDE_BUS, JourneyLegState.PENDING);
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(JOURNEY_ID, REVISION))
                .willReturn(List.of(wait, ride));

        JourneyEvaluationOutcome kept = writer.applyLaneReselection(outcome());

        assertThat(wait.getSelectedRouteOptionId()).isEqualTo(11L);
        assertThat(ride.getPlannedDurationSec()).isEqualTo(700);
        assertThat(kept.batch().boardingByLegIndex()).containsOnlyKeys(6);
        assertThat(kept.batch().assessments()).extracting(TransferRiskAssessment::transferIndex).containsExactly(1);
        assertThat(kept.batch().laneReselection()).isNull();
        verify(notificationRepository, never()).supersedeAllPendingByScope(anyLong(), anyInt(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("저장 직전 탔거나(대기 끝남) 다른 후보로 바뀌었으면 옮기지 않는다")
    void boardedOrChanged() {
        JourneyLeg boarded = leg(3, JourneyLegType.WAIT_BUS, JourneyLegState.COMPLETED);
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(JOURNEY_ID, REVISION))
                .willReturn(List.of(boarded, leg(4, JourneyLegType.RIDE_BUS, JourneyLegState.ACTIVE)));
        writer.applyLaneReselection(outcome());
        assertThat(boarded.getSelectedRouteOptionId()).isEqualTo(11L);

        JourneyLeg changed = leg(3, JourneyLegType.WAIT_BUS, JourneyLegState.ACTIVE);
        changed.selectRouteOption(12L, "R-741", RouteOptionSelectedBy.DEFAULT);
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(JOURNEY_ID, REVISION))
                .willReturn(List.of(changed, leg(4, JourneyLegType.RIDE_BUS, JourneyLegState.PENDING)));
        writer.applyLaneReselection(outcome());
        assertThat(changed.getSelectedRouteOptionId()).isEqualTo(12L);
    }
}
