package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyEvaluationOutcome;
import watoo.grd.nextroute.application.journey.dto.NotificationContext;
import watoo.grd.nextroute.application.journey.dto.TransferAssessmentBatch;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 알림을 <b>안 보내야 할 때 안 보내는지</b>를 본다.
 *
 * <p>가장 위험한 실수는 순위표를 숫자로만 비교하는 것이다. {@code UNKNOWN}(2)이
 * {@code CATCHABLE}(1)보다 나쁘게 매겨져 있어, 알릴 상태인지를 먼저 거르지 않으면 "모르겠다"가
 * 알림이 된다.
 */
@DisplayName("환승 위험 알림 판단")
class TransferRiskNotifierTest {

    private TransferConnectionRepository transferRepository;
    private JourneyNotificationRepository notificationRepository;
    private JourneyNotificationEnqueuer enqueuer;
    private JourneyNotificationProperties properties;
    private TransferRiskNotifier notifier;

    private TransferConnection transfer;

    @BeforeEach
    void setUp() {
        transferRepository = mock(TransferConnectionRepository.class);
        notificationRepository = mock(JourneyNotificationRepository.class);
        enqueuer = mock(JourneyNotificationEnqueuer.class);
        properties = new JourneyNotificationProperties();
        notifier = new TransferRiskNotifier(transferRepository, notificationRepository, enqueuer,
                properties);

        transfer = TransferConnection.builder()
                .journeyId(1L).routeRevision(1).transferIndex(0)
                .fromLegIndex(1).walkLegIndex(null).toLegIndex(2)
                .status(TransferStatus.UNKNOWN)
                .build();
        given(transferRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                1L, 1)).willReturn(List.of(transfer));
        given(enqueuer.enqueueTransferRisk(anyLong(), anyInt(), any(), any()))
                .willReturn("delivery-key");
    }

    @Test
    @DisplayName("순위표에서 모름은 서두름보다 나쁘게 매겨져 있다")
    void unknownOutranksCatchable() {
        assertThat(TransferStatus.UNKNOWN.severity())
                .as("이 관계 때문에 알릴 상태 필터가 숫자 비교보다 먼저 와야 한다")
                .isGreaterThan(TransferStatus.CATCHABLE.severity())
                .isLessThan(TransferStatus.AT_RISK.severity());
    }

    @Test
    @DisplayName("모름은 마커에 써지지 않는다")
    void unknownIsNeverMarked() {
        notify(TransferStatus.UNKNOWN);

        verify(enqueuer, never()).enqueueTransferRisk(anyLong(), anyInt(), any(), any());
        assertThat(transfer.getLastNotifiedStatus())
                .as("써지면 AT_RISK → UNKNOWN → AT_RISK 가 재알림이 된다")
                .isNull();
    }

    @Test
    @DisplayName("중복이라 아무 행도 안 생겨도 마커는 쓴다")
    void markerIsWrittenEvenWhenInsertIsNoOp() {
        notify(TransferStatus.AT_RISK);

        assertThat(transfer.getLastNotifiedStatus())
                .as("안 쓰면 10초마다 영원히 헛삽입한다")
                .isEqualTo("AT_RISK");
    }

    @Test
    @DisplayName("이번에 만든 발송 키는 밀어내기 대상에서 뺀다")
    void newlyCreatedKeysAreExcludedFromSupersede() {
        notify(TransferStatus.AT_RISK);

        verify(notificationRepository).supersedePendingByScope(
                eq(1L), eq(1), eq(JourneyNotificationScope.TRANSFER), eq(0),
                eq(List.of("delivery-key")));
    }

    @Test
    @DisplayName("kill switch 를 내리면 조회조차 하지 않는다")
    void killSwitchSkipsEverything() {
        properties.setEnqueueEnabled(false);

        notify(TransferStatus.AT_RISK);

        verify(transferRepository, never())
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(anyLong(), anyInt());
        assertThat(transfer.getLastNotifiedStatus()).isNull();
    }

    @Test
    @DisplayName("발송 정보가 없으면 아무것도 하지 않는다")
    void nullContextDoesNothing() {
        notifier.notifyWorsened(outcome(TransferStatus.AT_RISK), null);

        verify(enqueuer, never()).enqueueTransferRisk(anyLong(), anyInt(), any(), any());
        assertThat(transfer.getLastNotifiedStatus()).isNull();
    }

    @Test
    @DisplayName("채널 목록이 비면 마커도 안 쓴다")
    void emptyChannelsLeaveMarkerUntouched() {
        notifier.notifyWorsened(outcome(TransferStatus.AT_RISK),
                new NotificationContext(7L, 0, List.of()));

        assertThat(transfer.getLastNotifiedStatus())
                .as("마커를 앞당기면 그 환승은 채널이 생겨도 영영 알림이 없다")
                .isNull();
    }

    @Test
    @DisplayName("지나온 환승은 건너뛴다")
    void pastTransferIsSkipped() {
        notifier.notifyWorsened(outcome(TransferStatus.AT_RISK),
                new NotificationContext(7L, 5, List.of(NotificationChannel.PUSH)));

        verify(enqueuer, never()).enqueueTransferRisk(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("좋아지면 쌓인 알림을 밀어내고 마커를 비운다")
    void recoveryClearsMarker() {
        transfer.markNotified("AT_RISK");

        notify(TransferStatus.CATCHABLE);

        verify(notificationRepository).supersedeAllPendingByScope(
                1L, 1, JourneyNotificationScope.TRANSFER, 0,
                Set.of(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, JourneyCheckpoint.TRANSFER_RISK_MISSED));
        assertThat(transfer.getLastNotifiedStatus()).isNull();
    }

    @Test
    @DisplayName("모름에서는 밀어내지도 비우지도 않는다")
    void unknownDoesNotRecover() {
        transfer.markNotified("AT_RISK");

        notify(TransferStatus.UNKNOWN);

        verify(notificationRepository, never()).supersedeAllPendingByScope(
                anyLong(), anyInt(), any(), anyInt(), any());
        assertThat(transfer.getLastNotifiedStatus()).isEqualTo("AT_RISK");
    }

    @Test
    @DisplayName("같은 상태로는 다시 알리지 않는다")
    void sameStatusDoesNotNotifyAgain() {
        transfer.markNotified("AT_RISK");

        notify(TransferStatus.AT_RISK);

        verify(enqueuer, never()).enqueueTransferRisk(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("나빠지면 다시 알린다")
    void worseningNotifiesAgain() {
        transfer.markNotified("AT_RISK");

        notify(TransferStatus.MISSED);

        verify(enqueuer).enqueueTransferRisk(eq(1L), eq(1), any(), eq(NotificationChannel.PUSH));
        assertThat(transfer.getLastNotifiedStatus()).isEqualTo("MISSED");
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private void notify(TransferStatus status) {
        notifier.notifyWorsened(outcome(status),
                new NotificationContext(7L, 0, List.of(NotificationChannel.PUSH)));
    }

    private JourneyEvaluationOutcome outcome(TransferStatus status) {
        return new JourneyEvaluationOutcome(1L, 1,
                new TransferAssessmentBatch(List.of(assessment(status)), Map.of(), false), true);
    }

    private TransferRiskAssessment assessment(TransferStatus status) {
        Instant now = Instant.parse("2026-08-06T00:00:00Z");
        return new TransferRiskAssessment(0, 1, 2, status, now, now,
                status == TransferStatus.MISSED ? now.minusSeconds(60) : now.plusSeconds(60),
                status == TransferStatus.MISSED ? -60L : 60L,
                CurrentLegArrival.Quality.GOOD, now, CurrentLegArrival.Source.REALTIME_ARRIVAL, null);
    }
}
