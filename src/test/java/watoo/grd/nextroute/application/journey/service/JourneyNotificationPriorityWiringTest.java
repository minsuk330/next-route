package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 우선순위 배선(#451 PR-2). ① 적재 호출부마다 매핑 표의 값을 넘긴다 ② claim 순서 플래그가 SQL 을 고른다(BATCH·PULL 공통 입구).
 * 관리자 시험 알림의 값(2)은 {@code AdminJourneyNotificationTestServiceTest} 가 진다.
 */
@DisplayName("알림 우선순위 배선")
class JourneyNotificationPriorityWiringTest {

    private static final Instant NOW = Instant.parse("2026-10-16T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));

    private final JourneyNotificationRepository repository = mock(JourneyNotificationRepository.class);
    private final JourneyNotificationProperties properties = new JourneyNotificationProperties();
    private final JourneyNotificationMetrics metrics = new JourneyNotificationMetrics(new SimpleMeterRegistry());
    private final JourneyNotificationEnqueuer enqueuer = new JourneyNotificationEnqueuer(repository,
            properties, metrics, new ObjectMapper(), CLOCK);

    private void stubEnqueue() {
        given(repository.enqueue(anyLong(), anyInt(), anyString(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyShort(), any())).willReturn(List.of(1L));
    }

    private void verifyPriority(String checkpoint, int priority) {
        verify(repository).enqueue(anyLong(), anyInt(), anyString(), anyInt(), eq(checkpoint), anyString(),
                anyString(), anyString(), any(), any(), any(), eq((short) priority), any());
    }

    @Test
    @DisplayName("놓침 0 · 빠듯 0")
    void transferRiskIsUrgent() {
        stubEnqueue();
        enqueuer.enqueueTransferRisk(1L, 1, assessment(TransferStatus.MISSED), NotificationChannel.PUSH);
        enqueuer.enqueueTransferRisk(1L, 1, assessment(TransferStatus.AT_RISK), NotificationChannel.PUSH);

        verifyPriority("TRANSFER_RISK_MISSED", 0);
        verifyPriority("TRANSFER_RISK_AT_RISK", 0);
    }

    @Test
    @DisplayName("접근(N2) 1")
    void approachIsSoon() {
        stubEnqueue();
        enqueuer.enqueueTargetApproach(1L, 1, assessment(TransferStatus.AT_RISK), NotificationChannel.PUSH, NOW);

        verifyPriority("TRANSFER_TARGET_APPROACHING", 1);
    }

    @Test
    @DisplayName("재탐색 제안 2 · 보드 push 2")
    void rerouteAndBoardAreRelaxed() {
        stubEnqueue();
        enqueuer.enqueueRerouteSuggested(1L, 1, 7L, 120, NotificationChannel.PUSH, NOW, NOW.plusSeconds(300));
        enqueuer.enqueueBoardPush(1L, 1, 8L, 1, Map.of("gain", 60), NotificationChannel.PUSH, NOW,
                NOW.plusSeconds(300));

        verify(repository, org.mockito.Mockito.times(2)).enqueue(anyLong(), anyInt(), anyString(), anyInt(),
                eq("REROUTE_SUGGESTED"), anyString(), anyString(), anyString(), any(), any(), any(),
                eq((short) 2), any());
    }

    @Test
    @DisplayName("claim 순서 SCHEDULED(기본) → 옛 SQL 만 부른다")
    void scheduledUsesLegacyClaim() {
        stateService().claimBatch(NOW, 5);

        verify(repository).claimBatchForProcessing(eq(NOW), eq(5), any());
        verify(repository, never()).claimBatchForProcessingPriorityFirst(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("claim 순서 PRIORITY_FIRST → 우선순위 SQL, aging 기준 = 앱 시계 지금 − aging-seconds")
    void priorityFirstUsesPriorityClaim() {
        properties.getRelay().setClaimOrder(JourneyNotificationProperties.ClaimOrder.PRIORITY_FIRST);
        properties.getRelay().setAgingSeconds(45);

        stateService().claimBatch(NOW, 5);

        LocalDateTime auditNow = LocalDateTime.now(CLOCK);
        verify(repository).claimBatchForProcessingPriorityFirst(eq(NOW), eq(auditNow.minusSeconds(45)), eq(5),
                eq(auditNow));
        verify(repository, never()).claimBatchForProcessing(any(), anyInt(), any());
    }

    private JourneyNotificationOutboxStateService stateService() {
        return new JourneyNotificationOutboxStateService(repository, mock(JourneySessionRepository.class),
                mock(TransferConnectionRepository.class), properties, metrics, CLOCK, new JourneyRetryBackoff(properties));
    }

    private static TransferRiskAssessment assessment(TransferStatus status) {
        return new TransferRiskAssessment(0, 1, 3, status, NOW, NOW.plusSeconds(60),
                NOW.plusSeconds(100), 40L, CurrentLegArrival.Quality.GOOD, NOW, null, null);
    }
}
