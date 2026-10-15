package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.messaging.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.exception.NotificationSkippedException;
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;
import watoo.grd.nextroute.application.journey.port.out.FirebaseMessagingClient;
import watoo.grd.nextroute.application.journey.port.out.FirebaseMessagingClient.PushResult;
import watoo.grd.nextroute.application.journey.port.out.PushMessage;
import watoo.grd.nextroute.application.push.service.PushTokenDeactivationService;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;
import watoo.grd.nextroute.domain.push.entity.PushEnvironment;
import watoo.grd.nextroute.domain.push.entity.PushPlatform;
import watoo.grd.nextroute.domain.push.entity.PushProvider;
import watoo.grd.nextroute.domain.push.entity.UserPushToken;
import watoo.grd.nextroute.domain.push.repository.UserPushTokenRepository;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 악화 알림 피로 정책(#428 PR-1a)의 전송 계층 계약.
 *
 * <p>조용히 망가지는 것이 셋이다. 정책을 껐는데 메시지가 바뀌면 운영 알림이 예고 없이 달라지고, 조용하라고
 * 보낸 갱신이 채널 없는 Android 에 가면 오히려 울리고, 갱신 열쇠가 환승 단위로 남으면 환승이 둘일 때 알림이
 * 둘 쌓인다.
 */
@DisplayName("악화 알림 피로 정책 — 전송")
class AlertFatigueTransportTest {

    private static final Instant NOW = Instant.parse("2026-10-15T05:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JourneyNotificationProperties properties = new JourneyNotificationProperties();
    private final FcmMessageFactory factory = new FcmMessageFactory(properties);

    private static NotificationSendCommand command(JourneyCheckpoint checkpoint, int scopeId, String delivery) {
        var payload = MAPPER.createObjectNode().put("status", "AT_RISK");
        if (delivery != null) {
            payload.put("delivery", delivery);
        }
        JourneyNotificationScope scope = checkpoint == JourneyCheckpoint.REROUTE_SUGGESTED
                ? JourneyNotificationScope.JOURNEY : JourneyNotificationScope.TRANSFER;
        return new NotificationSendCommand(7L, 42L, 11L, 3, scope, scopeId, checkpoint,
                NotificationChannel.PUSH, 5L, 9L, payload, NOW.plusSeconds(600));
    }

    private static String json(PushMessage message) throws IOException {
        Message built = FirebaseAdminMessagingClient.SdkDispatcher.toMessage(message, "token-a");
        return GsonFactory.getDefaultInstance().toString(built);
    }

    @Nested
    @DisplayName("메시지 조립")
    class Factory {

        @Test
        @DisplayName("정책을 끄면 지금 메시지 그대로다 — 갱신 열쇠·tag·채널·data 어느 것도 안 바뀐다")
        void offIsUnchanged() throws IOException {
            PushMessage message = factory.from(command(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, 0, "QUIET"), NOW);

            assertThat(message.collapseKey()).isEqualTo("j11r3T0");
            assertThat(message.delivery()).isNull();
            assertThat(message.androidTag()).isNull();
            assertThat(message.androidChannelId()).isNull();
            assertThat(message.data()).doesNotContainKey("delivery");
            assertThat(json(message))
                    .as("payload 에 QUIET 가 적혀 있어도 정책이 꺼져 있으면 발송에 아무 흔적이 없어야 한다")
                    .doesNotContain("interruption-level", "\"tag\"", "channel_id", "journey_quiet");
        }

        @Test
        @DisplayName("켜면 위험·재탐색이 경로 버전 하나의 열쇠를 같이 쓴다 — 환승이 둘이어도 한 자리")
        void routeRevisionCollapseKey() {
            properties.getFatigue().setEnabled(true);

            PushMessage first = factory.from(command(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, 0, null), NOW);
            PushMessage second = factory.from(command(JourneyCheckpoint.TRANSFER_RISK_MISSED, 1, "QUIET"), NOW);
            PushMessage reroute = factory.from(command(JourneyCheckpoint.REROUTE_SUGGESTED, 55, "QUIET"), NOW);

            assertThat(first.collapseKey()).isEqualTo("j11r3A")
                    .isEqualTo(second.collapseKey()).isEqualTo(reroute.collapseKey());
            assertThat(first.androidTag())
                    .as("collapse key 는 아직 전달 안 된 것만 합친다 — 화면에 떠 있는 알림은 tag 로 덮는다")
                    .isEqualTo(first.collapseKey());
        }

        @Test
        @DisplayName("울림은 조용한 채널을 지정하지 않고, 조용한 갱신만 지정한다")
        void quietChannelOnlyForQuiet() {
            properties.getFatigue().setEnabled(true);

            PushMessage alerting = factory.from(command(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, 0, "ALERTING"), NOW);
            PushMessage quiet = factory.from(command(JourneyCheckpoint.TRANSFER_RISK_MISSED, 0, "QUIET"), NOW);

            assertThat(alerting.delivery()).isEqualTo(PushMessage.Delivery.ALERTING);
            assertThat(alerting.androidChannelId()).isNull();
            assertThat(alerting.data()).containsEntry("delivery", "ALERTING");
            assertThat(quiet.delivery()).isEqualTo(PushMessage.Delivery.QUIET);
            assertThat(quiet.androidChannelId()).isEqualTo("journey_quiet");
            assertThat(quiet.data()).containsEntry("delivery", "QUIET");
        }

        @Test
        @DisplayName("울림 표시가 없으면(직렬화 실패·정책 이전 행) 울림으로 본다")
        void missingDeliveryAlerts() {
            properties.getFatigue().setEnabled(true);
            NotificationSendCommand noPayload = new NotificationSendCommand(7L, 42L, 11L, 3,
                    JourneyNotificationScope.TRANSFER, 0, JourneyCheckpoint.TRANSFER_RISK_AT_RISK,
                    NotificationChannel.PUSH, 5L, 9L, null, NOW.plusSeconds(600));

            assertThat(factory.from(noPayload, NOW).delivery())
                    .as("조용히 사라지는 쪽보다 한 번 더 울리는 쪽이 낫다")
                    .isEqualTo(PushMessage.Delivery.ALERTING);
        }

        @Test
        @DisplayName("접근 알림은 정책 밖이다 — 켜도 지금 그대로")
        void approachIsOutsidePolicy() {
            properties.getFatigue().setEnabled(true);

            PushMessage approach = factory.from(
                    command(JourneyCheckpoint.TRANSFER_TARGET_APPROACHING, 0, "QUIET"), NOW);

            assertThat(approach.collapseKey()).isEqualTo("j11r3T0");
            assertThat(approach.delivery()).isNull();
            assertThat(approach.androidTag()).isNull();
        }
    }

    @Nested
    @DisplayName("FCM 메시지")
    class Sdk {

        @Test
        @DisplayName("조용한 갱신은 iOS passive·Android 조용한 채널·tag 를 싣는다")
        void quietMessage() throws IOException {
            properties.getFatigue().setEnabled(true);

            String body = json(factory.from(command(JourneyCheckpoint.TRANSFER_RISK_MISSED, 0, "QUIET"), NOW));

            assertThat(body).contains("\"interruption-level\":\"passive\"", "\"channel_id\":\"journey_quiet\"",
                    "\"tag\":\"j11r3A\"", "\"apns-collapse-id\":\"j11r3A\"");
        }

        @Test
        @DisplayName("울림은 tag 만 싣고 passive·채널은 없다")
        void alertingMessage() throws IOException {
            properties.getFatigue().setEnabled(true);

            String body = json(factory.from(command(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, 0, null), NOW));

            assertThat(body).contains("\"tag\":\"j11r3A\"").doesNotContain("interruption-level", "channel_id");
        }
    }

    @Nested
    @DisplayName("발송 대상 기기")
    class Target {

        private final UserPushTokenRepository tokens = mock(UserPushTokenRepository.class);
        private final RecordingClient client = new RecordingClient();
        private FcmNotificationSender sender;

        @BeforeEach
        void setUp() {
            properties.getFatigue().setEnabled(true);
            sender = new FcmNotificationSender(tokens, client, factory, mock(PushTokenDeactivationService.class),
                    new JourneyNotificationMetrics(new SimpleMeterRegistry()),
                    Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
        }

        private void givenToken(PushPlatform platform, boolean quietSupported) {
            UserPushToken token = UserPushToken.builder().userId(42L).platform(platform)
                    .provider(PushProvider.FCM).token("token-a").environment(PushEnvironment.PRODUCTION)
                    .lastSeenAt(NOW.minusSeconds(60)).build();
            ReflectionTestUtils.setField(token, "id", 1L);
            ReflectionTestUtils.setField(token, "quietUpdateSupported", quietSupported);
            given(tokens.findFirstByUserIdAndEnabledTrueAndDeletedAtIsNullOrderByLastSeenAtDescIdDesc(42L))
                    .willReturn(Optional.of(token));
        }

        @Test
        @DisplayName("조용한 채널이 없는 Android 에는 조용한 갱신을 보내지 않는다 — 보내면 기본 채널로 울린다")
        void skipsQuietOnAndroidWithoutChannel() {
            givenToken(PushPlatform.ANDROID, false);

            assertThatThrownBy(() -> sender.send(command(JourneyCheckpoint.TRANSFER_RISK_MISSED, 0, "QUIET")))
                    .isInstanceOf(NotificationSkippedException.class)
                    .extracting(e -> ((NotificationSkippedException) e).getReason())
                    .isEqualTo("quiet-skipped-android");
            assertThat(client.calls).isZero();
        }

        @Test
        @DisplayName("채널을 알린 Android 와 iOS 에는 조용한 갱신을 보낸다")
        void sendsQuietWhereSupported() {
            givenToken(PushPlatform.ANDROID, true);
            sender.send(command(JourneyCheckpoint.TRANSFER_RISK_MISSED, 0, "QUIET"));

            givenToken(PushPlatform.IOS, false);
            sender.send(command(JourneyCheckpoint.TRANSFER_RISK_MISSED, 0, "QUIET"));

            assertThat(client.calls).isEqualTo(2);
        }

        @Test
        @DisplayName("울림은 채널 능력과 무관하게 보낸다 — 구버전 앱도 첫 울림은 받는다")
        void alertingIgnoresCapability() {
            givenToken(PushPlatform.ANDROID, false);

            sender.send(command(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, 0, null));

            assertThat(client.calls).isEqualTo(1);
        }
    }

    private static class RecordingClient implements FirebaseMessagingClient {
        int calls;

        @Override
        public PushResult send(PushMessage message, String token) {
            calls++;
            return PushResult.ok();
        }
    }
}
