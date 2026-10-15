package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties.RetryPolicy;
import watoo.grd.nextroute.application.journey.dto.ClaimedNotification;
import watoo.grd.nextroute.application.journey.exception.StaleClaimException;
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotification;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 일시 실패 기록이 재시도 정책(#451 PR-3)을 따른다. 상한은 실제 발송 시도({@code attempt_count − requeue_count})로 세고,
 * 다음 시도 시각이 만료를 넘으면 기존 {@code expired-before-retry} 취소 경로로 끝난다. 증서 조건부 기록은 그대로다.
 */
@DisplayName("일시 실패 → 재시도 정책")
class JourneyNotificationRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-16T09:00:00Z");

    private final JourneyNotificationRepository notifications = mock(JourneyNotificationRepository.class);

    private JourneyNotificationOutboxStateService service(JourneyNotificationProperties properties) {
        given(notifications.finishIfClaimed(anyLong(), anyInt(), anyString(), any(), any())).willReturn(1);
        given(notifications.releaseToPendingIfClaimed(anyLong(), anyInt(), any(), any(), any())).willReturn(1);
        return new JourneyNotificationOutboxStateService(notifications, mock(JourneySessionRepository.class),
                mock(TransferConnectionRepository.class), properties,
                new JourneyNotificationMetrics(new SimpleMeterRegistry()), Clock.fixed(NOW, ZoneId.of("Asia/Seoul")),
                new JourneyRetryBackoff(properties, new SplittableRandom(3)));
    }

    private static JourneyNotificationProperties exponentialNoJitter() {
        JourneyNotificationProperties properties = new JourneyNotificationProperties();
        properties.setRetryPolicy(RetryPolicy.EXPONENTIAL);
        properties.setClaimTimeoutSeconds(45L);
        properties.getRetry().setJitter(0);
        return properties;
    }

    @Test
    @DisplayName("EXPONENTIAL: 세 번째 실제 시도 실패 → 다음 시도는 지금 + 60초(24 × 2² = 96 → 최대 60)")
    void exponentialSchedulesGrowingDelay() {
        JourneyNotificationOutboxStateService service = service(exponentialNoJitter());
        given(notifications.findById(1L)).willReturn(Optional.of(row(1L, 3, 0, NOW.plusSeconds(1800))));

        service.markTransientFailure(new ClaimedNotification(1L, 3), "fcm: UNAVAILABLE");

        verify(notifications).releaseToPendingIfClaimed(eq(1L), eq(3), eq("fcm: UNAVAILABLE"),
                eq(NOW.plusSeconds(60)), any());
    }

    @Test
    @DisplayName("EXPONENTIAL: 회수 몫은 빼고 센다 — attempt 9, requeue 2 = 실제 7 → 아직 재시도")
    void exponentialCapExcludesRequeues() {
        JourneyNotificationOutboxStateService service = service(exponentialNoJitter());
        given(notifications.findById(2L)).willReturn(Optional.of(row(2L, 9, 2, NOW.plusSeconds(1800))));

        service.markTransientFailure(new ClaimedNotification(2L, 9), "fcm: UNAVAILABLE");

        verify(notifications).releaseToPendingIfClaimed(eq(2L), eq(9), any(), eq(NOW.plusSeconds(60)), any());
        verify(notifications, never()).finishIfClaimed(anyLong(), anyInt(), anyString(), any(), any());
    }

    @Test
    @DisplayName("EXPONENTIAL: 실제 시도 8 에서 DEAD")
    void exponentialCapEightIsDead() {
        JourneyNotificationOutboxStateService service = service(exponentialNoJitter());
        given(notifications.findById(3L)).willReturn(Optional.of(row(3L, 8, 0, NOW.plusSeconds(1800))));

        service.markTransientFailure(new ClaimedNotification(3L, 8), "fcm: UNAVAILABLE");

        verify(notifications).finishIfClaimed(eq(3L), eq(8), eq("DEAD"), eq("max-retry: fcm: UNAVAILABLE"), any());
        verify(notifications, never()).releaseToPendingIfClaimed(anyLong(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("EXPONENTIAL: 다음 시도 시각이 만료를 넘으면 expired-before-retry 로 취소")
    void exponentialBeyondExpiryCancels() {
        JourneyNotificationOutboxStateService service = service(exponentialNoJitter());
        // 두 번째 시도 뒤 48초 — 만료 40초 뒤라 넘는다
        given(notifications.findById(4L)).willReturn(Optional.of(row(4L, 2, 0, NOW.plusSeconds(40))));

        service.markTransientFailure(new ClaimedNotification(4L, 2), "fcm: UNAVAILABLE");

        verify(notifications).finishIfClaimed(eq(4L), eq(2), eq("CANCELED"), eq("expired-before-retry"), any());
        verify(notifications, never()).releaseToPendingIfClaimed(anyLong(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("EXPONENTIAL: 증서가 낡았으면 정책과 무관하게 StaleClaim(울타리 유지)")
    void exponentialKeepsFencing() {
        JourneyNotificationOutboxStateService service = service(exponentialNoJitter());
        given(notifications.findById(5L)).willReturn(Optional.of(row(5L, 3, 0, NOW.plusSeconds(1800))));

        assertThatThrownBy(() -> service.markTransientFailure(new ClaimedNotification(5L, 2), "x"))
                .isInstanceOf(StaleClaimException.class);
        verify(notifications, never()).releaseToPendingIfClaimed(anyLong(), anyInt(), any(), any(), any());
        verify(notifications, never()).finishIfClaimed(anyLong(), anyInt(), anyString(), any(), any());
    }

    @Test
    @DisplayName("FIXED(기본): 지금과 같다 — 늘 지금 + 60초, 실제 시도 3 에서 DEAD")
    void fixedIsUnchanged() {
        JourneyNotificationProperties properties = new JourneyNotificationProperties();
        JourneyNotificationOutboxStateService service = service(properties);
        given(notifications.findById(6L)).willReturn(Optional.of(row(6L, 2, 0, NOW.plusSeconds(1800))));
        given(notifications.findById(7L)).willReturn(Optional.of(row(7L, 3, 0, NOW.plusSeconds(1800))));

        service.markTransientFailure(new ClaimedNotification(6L, 2), "fcm: UNAVAILABLE");
        service.markTransientFailure(new ClaimedNotification(7L, 3), "fcm: UNAVAILABLE");

        verify(notifications).releaseToPendingIfClaimed(eq(6L), eq(2), any(), eq(NOW.plusSeconds(60)), any());
        verify(notifications).finishIfClaimed(eq(7L), eq(3), eq("DEAD"), any(), any());
    }

    private static JourneyNotification row(long id, int attemptCount, int requeueCount, Instant expiresAt) {
        JourneyNotification n = JourneyNotification.builder()
                .journeyId(10L).userId(20L).routeRevision(1).commandVersion(1).evaluationVersion(1)
                .scopeType(JourneyNotificationScope.TRANSFER).scopeId(0)
                .checkpoint(JourneyCheckpoint.TRANSFER_RISK_AT_RISK)
                .channel(NotificationChannel.PUSH).logicalCheckpointKey("k" + id).deliveryKey("d" + id)
                .scheduledAt(NOW.minusSeconds(5)).expiresAt(expiresAt)
                .payload(new ObjectMapper().createObjectNode())
                .build();
        ReflectionTestUtils.setField(n, "id", id);
        ReflectionTestUtils.setField(n, "status", JourneyNotificationStatus.PROCESSING);
        ReflectionTestUtils.setField(n, "attemptCount", attemptCount);
        ReflectionTestUtils.setField(n, "requeueCount", requeueCount);
        return n;
    }
}
