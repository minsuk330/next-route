package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.DetectionStamp;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;
import watoo.grd.nextroute.application.journey.port.out.PushMessage;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationTier;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 위험 알림 payload 가 처음 본 시각·등급·출처·보류를 싣는 계약(#446). 발송 접수 쪽이 이 칸으로 잰다.
 */
@DisplayName("위험 알림 payload — 처음 본 시각")
class JourneyNotificationDetectionPayloadTest {

    private static final Instant NOW = Instant.parse("2026-10-16T09:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JourneyNotificationRepository repository = mock(JourneyNotificationRepository.class);
    private final JourneyNotificationEnqueuer enqueuer = new JourneyNotificationEnqueuer(repository,
            new JourneyNotificationProperties(), new JourneyNotificationMetrics(new SimpleMeterRegistry()),
            MAPPER, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

    @Test
    @DisplayName("처음 본 시각이 있으면 firstSeenAt·seenTier·seenSource·holdMs 를 싣는다")
    void carriesDetection() throws Exception {
        given(repository.enqueue(anyLong(), anyInt(), anyString(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), any())).willReturn(List.of(1L));

        enqueuer.enqueueTransferRisk(1L, 1, assessment(), NotificationChannel.PUSH, Duration.ofSeconds(17),
                PushMessage.Delivery.ALERTING, null, DetectionStamp.feed(NOW.minusSeconds(4), ReevaluationTier.CAUTION));

        JsonNode payload = MAPPER.readTree(capturedPayload());
        assertThat(payload.path("firstSeenAt").asText()).isEqualTo(NOW.minusSeconds(4).toString());
        assertThat(payload.path("seenTier").asText()).isEqualTo("CAUTION");
        assertThat(payload.path("seenSource").asText()).isEqualTo("feed");
        assertThat(payload.path("holdMs").asLong()).isEqualTo(17_000L);
        assertThat(payload.path("delivery").asText()).isEqualTo("ALERTING");
    }

    @Test
    @DisplayName("처음 본 시각이 없으면(옛 호출) 칸이 없다 — payload 는 예전 그대로")
    void omitsWithoutDetection() throws Exception {
        given(repository.enqueue(anyLong(), anyInt(), anyString(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any(), any())).willReturn(List.of(1L));

        enqueuer.enqueueTransferRisk(1L, 1, assessment(), NotificationChannel.PUSH);

        JsonNode payload = MAPPER.readTree(capturedPayload());
        assertThat(payload.has("firstSeenAt")).isFalse();
        assertThat(payload.has("holdMs")).isFalse();
    }

    private String capturedPayload() {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(repository).enqueue(anyLong(), anyInt(), anyString(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), payload.capture(), any());
        return payload.getValue();
    }

    private static TransferRiskAssessment assessment() {
        return new TransferRiskAssessment(0, 1, 3, TransferStatus.AT_RISK, NOW, NOW.plusSeconds(60),
                NOW.plusSeconds(100), 40L, CurrentLegArrival.Quality.GOOD, NOW, null, null);
    }
}
