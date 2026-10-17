package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.ClaimedNotification;
import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.dto.SendTask;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 알림 SLO 지표(#446)의 계약. 발송 접수 때 "처음 본 → 접수"·보류·만료 뒤 발송을 재고, 보냈어야 했는데 끝난 행
 * (만료·재시도 전 만료·DEAD·영구 실패)만 잃은 것으로 센다 — 대체·여정 종료 같은 정상 취소는 세지 않는다.
 */
@DisplayName("알림 SLO 지표 — 처음부터 끝까지·보류·잃음·만료 뒤 발송")
class JourneyNotificationSloMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-16T09:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JourneyNotificationRepository notifications = mock(JourneyNotificationRepository.class);
    private final JourneySessionRepository sessions = mock(JourneySessionRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JourneyNotificationProperties properties = new JourneyNotificationProperties();

    {
        // 고정 백오프 기대값으로 쓴 시험(#451 후속에서 기본이 EXPONENTIAL 로 바뀜).
        properties.setRetryPolicy(JourneyNotificationProperties.RetryPolicy.FIXED);
    }
    private JourneyNotificationOutboxStateService service;

    @BeforeEach
    void setUp() {
        service = new JourneyNotificationOutboxStateService(notifications, sessions,
                mock(TransferConnectionRepository.class), properties, new JourneyNotificationMetrics(registry),
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")), new JourneyRetryBackoff(properties));
        // 증서 조건부 UPDATE(#451)는 늘 1행 성공으로 둔다 — 이 시험은 지표를 본다.
        given(notifications.markSentIfClaimed(anyLong(), anyInt(), any(), any())).willReturn(1);
        given(notifications.finishIfClaimed(anyLong(), anyInt(), anyString(), any(), any())).willReturn(1);
        given(notifications.releaseToPendingIfClaimed(anyLong(), anyInt(), any(), any(), any())).willReturn(1);
    }

    /** 행의 attempt_count 기본값(0)이 증서다. */
    private static ClaimedNotification claim(long id) {
        return claim(id, 0);
    }

    private static ClaimedNotification claim(long id, int token) {
        return new ClaimedNotification(id, token);
    }

    /** {@code findWithJourney} 한 행 — 여정 칸은 비운다(만료·여정 끝 판정은 여정 행 없이도 나온다). */
    private static List<Object[]> withJourney(JourneyNotification n) {
        return List.<Object[]>of(new Object[]{n, null});
    }

    @Test
    @DisplayName("접수 때 처음 본 시각부터 잰다 — 등급·종류·울림·출처 tag, 보류는 payload 의 적재 때 값")
    void recordsEndToEndWithTags() {
        ObjectNode payload = payload("ALERTING");
        payload.put("firstSeenAt", NOW.minusSeconds(21).toString());
        payload.put("seenTier", "CAUTION");
        payload.put("seenSource", "feed");
        payload.put("holdMs", 17_000);
        given(notifications.findById(1L)).willReturn(Optional.of(row(1L, JourneyCheckpoint.TRANSFER_RISK_AT_RISK,
                payload, NOW.plusSeconds(180), JourneyNotificationStatus.PROCESSING)));

        service.markSent(claim(1L));

        var e2e = registry.find(JourneyNotificationMetrics.E2E).tag("tier", "CAUTION")
                .tag("checkpoint", "TRANSFER_RISK_AT_RISK").tag("delivery", "ALERTING").tag("source", "feed").timer();
        assertThat(e2e).isNotNull();
        assertThat(e2e.count()).isEqualTo(1);
        assertThat(e2e.totalTime(TimeUnit.SECONDS)).isEqualTo(21.0);
        var hold = registry.find(JourneyNotificationMetrics.HOLD).tag("delivery", "ALERTING").timer();
        assertThat(hold.totalTime(TimeUnit.SECONDS)).isEqualTo(17.0);
        assertThat(registry.find(JourneyNotificationMetrics.SENT_AFTER_EXPIRY).counter()).isNull();
    }

    @Test
    @DisplayName("재확인 때 읽은 값으로 접수를 기록하면 알림 행을 다시 읽지 않는다 — 지표는 같게 잰다(#461 후속)")
    void markSentWithTaskDoesNotReread() {
        ObjectNode payload = payload("ALERTING");
        payload.put("firstSeenAt", NOW.minusSeconds(21).toString());
        payload.put("seenTier", "CAUTION");
        payload.put("seenSource", "feed");
        JourneyNotification n = row(9L, JourneyCheckpoint.TRANSFER_RISK_AT_RISK, payload, NOW.minusMillis(1),
                JourneyNotificationStatus.PROCESSING);
        SendTask task = new SendTask(new NotificationSendCommand(9L, 1L, 10L, 1, JourneyNotificationScope.TRANSFER, 0,
                JourneyCheckpoint.TRANSFER_RISK_AT_RISK, NotificationChannel.PUSH, 0, 0, payload, NOW.minusMillis(1)),
                n.getCreatedAt());

        service.markSent(claim(9L), task);

        verify(notifications, never()).findById(anyLong());
        verify(notifications).markSentIfClaimed(eq(9L), eq(0), eq(NOW), any());
        assertThat(registry.find(JourneyNotificationMetrics.E2E).tag("tier", "CAUTION").timer().totalTime(
                TimeUnit.SECONDS)).isEqualTo(21.0);
        assertThat(registry.find(JourneyNotificationMetrics.SENT_AFTER_EXPIRY)
                .tag("checkpoint", "TRANSFER_RISK_AT_RISK").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("처음 본 시각이 없으면 못 잰 것으로 센다(재탐색·접근·정책 이전 행)")
    void countsUnmeasured() {
        given(notifications.findById(2L)).willReturn(Optional.of(row(2L, JourneyCheckpoint.REROUTE_SUGGESTED,
                payload("ALERTING"), NOW.plusSeconds(60), JourneyNotificationStatus.PROCESSING)));

        service.markSent(claim(2L));

        assertThat(registry.find(JourneyNotificationMetrics.E2E).timer()).isNull();
        assertThat(registry.find(JourneyNotificationMetrics.E2E_UNMEASURED)
                .tag("checkpoint", "REROUTE_SUGGESTED").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("만료 시각을 넘겨 접수되면 '만료 뒤 발송' 으로 센다 — 발송 직전 재확인 뒤 공급자 왕복이 길 때")
    void countsSentAfterExpiry() {
        given(notifications.findById(3L)).willReturn(Optional.of(row(3L, JourneyCheckpoint.TRANSFER_RISK_MISSED,
                payload("ALERTING"), NOW.minusMillis(1), JourneyNotificationStatus.PROCESSING)));

        service.markSent(claim(3L));

        assertThat(registry.find(JourneyNotificationMetrics.SENT_AFTER_EXPIRY)
                .tag("checkpoint", "TRANSFER_RISK_MISSED").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("발송 직전에 만료된 행은 잃은 것(expired)이다")
    void expiredBeforeSendIsLost() {
        given(notifications.findWithJourney(4L)).willReturn(withJourney(row(4L,
                JourneyCheckpoint.TRANSFER_RISK_AT_RISK, payload("ALERTING"), NOW, JourneyNotificationStatus.PROCESSING)));

        assertThat(service.loadTask(claim(4L))).isEmpty();

        assertThat(lost("TRANSFER_RISK_AT_RISK", "ALERTING", "expired")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("여정이 끝나 취소된 행은 잃은 것이 아니다(정상 취소)")
    void journeyEndedIsNotLost() {
        // 여정 행이 없으면 한 문장 조회의 두 번째 칸이 null 이다.
        given(notifications.findWithJourney(5L)).willReturn(withJourney(row(5L, JourneyCheckpoint.TRANSFER_RISK_AT_RISK,
                payload("ALERTING"), NOW.plusSeconds(180), JourneyNotificationStatus.PROCESSING)));

        assertThat(service.loadTask(claim(5L))).isEmpty();

        assertThat(registry.find(JourneyNotificationMetrics.LOST).counter()).isNull();
    }

    @Test
    @DisplayName("재시도 예산을 다 쓰면 dead, 재시도 시각이 만료를 넘기면 expired-before-retry, 남으면 잃지 않는다")
    void transientFailureOutcomes() {
        JourneyNotification dead = row(6L, JourneyCheckpoint.TRANSFER_RISK_AT_RISK, payload("ALERTING"),
                NOW.plusSeconds(1800), JourneyNotificationStatus.PROCESSING);
        ReflectionTestUtils.setField(dead, "attemptCount", properties.getMaxAttempts());
        JourneyNotification late = row(7L, JourneyCheckpoint.TRANSFER_RISK_MISSED, payload(null),
                NOW.plusSeconds(30), JourneyNotificationStatus.PROCESSING);
        ReflectionTestUtils.setField(late, "attemptCount", 1);
        JourneyNotification retry = row(8L, JourneyCheckpoint.TRANSFER_RISK_AT_RISK, payload("ALERTING"),
                NOW.plusSeconds(1800), JourneyNotificationStatus.PROCESSING);
        ReflectionTestUtils.setField(retry, "attemptCount", 1);
        given(notifications.findById(6L)).willReturn(Optional.of(dead));
        given(notifications.findById(7L)).willReturn(Optional.of(late));
        given(notifications.findById(8L)).willReturn(Optional.of(retry));

        service.markTransientFailure(claim(6L, properties.getMaxAttempts()), "fcm: UNAVAILABLE");
        service.markTransientFailure(claim(7L, 1), "fcm: UNAVAILABLE");
        service.markTransientFailure(claim(8L, 1), "fcm: UNAVAILABLE");

        assertThat(lost("TRANSFER_RISK_AT_RISK", "ALERTING", "dead")).isEqualTo(1.0);
        // payload 에 울림 종류가 없으면(피로 정책 꺼짐) none — 발송기는 울림으로 보낸다
        assertThat(lost("TRANSFER_RISK_MISSED", "none", "expired-before-retry")).isEqualTo(1.0);
        // 상태 변경은 증서 조건부 네이티브 UPDATE 다(#451) — 엔티티는 그대로이고 저장소 호출로 본다.
        verify(notifications).releaseToPendingIfClaimed(eq(8L), eq(1), eq("fcm: UNAVAILABLE"),
                eq(NOW.plus(properties.backoff())), any());
        assertThat(registry.find(JourneyNotificationMetrics.LOST).counters()).hasSize(2);
    }

    @Test
    @DisplayName("영구 실패는 코드만 tag 로 남긴다(failed:UNREGISTERED)")
    void permanentFailureIsLostWithCode() {
        given(notifications.findById(9L)).willReturn(Optional.of(row(9L, JourneyCheckpoint.TRANSFER_RISK_AT_RISK,
                payload("QUIET"), NOW.plusSeconds(180), JourneyNotificationStatus.PROCESSING)));

        service.markPermanentFailure(claim(9L), "fcm: UNREGISTERED");

        assertThat(lost("TRANSFER_RISK_AT_RISK", "QUIET", "failed:UNREGISTERED")).isEqualTo(1.0);
    }

    private double lost(String checkpoint, String delivery, String reason) {
        var counter = registry.find(JourneyNotificationMetrics.LOST).tag("checkpoint", checkpoint)
                .tag("delivery", delivery).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private static ObjectNode payload(String delivery) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("transferIndex", 0);
        if (delivery != null) {
            node.put("delivery", delivery);
        }
        return node;
    }

    private static JourneyNotification row(long id, JourneyCheckpoint checkpoint, ObjectNode payload,
                                           Instant expiresAt, JourneyNotificationStatus status) {
        JourneyNotification n = JourneyNotification.builder()
                .journeyId(10L).userId(20L).routeRevision(1).commandVersion(1).evaluationVersion(1)
                .scopeType(JourneyNotificationScope.TRANSFER).scopeId(0).checkpoint(checkpoint)
                .channel(NotificationChannel.PUSH).logicalCheckpointKey("k" + id).deliveryKey("d" + id)
                .scheduledAt(NOW.minusSeconds(5)).expiresAt(expiresAt).payload(payload)
                .build();
        ReflectionTestUtils.setField(n, "id", id);
        ReflectionTestUtils.setField(n, "status", status);
        return n;
    }
}
