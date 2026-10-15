package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.exception.NotificationSendException;
import watoo.grd.nextroute.application.journey.port.out.FirebaseMessagingClient.PushResult;
import watoo.grd.nextroute.application.journey.port.out.PushMessage;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 경계가 예산과 오류를 어떻게 넘기는지. SDK 없이 검증한다.
 *
 * <p>시한이 이 테스트의 핵심이다. 발송이 회수 주기(3분)보다 오래 걸리면 회수 루프가 아직
 * 발송 중인 행을 다시 집어 가 같은 알림이 두 번 나간다.
 */
@DisplayName("FCM 호출 경계")
class FirebaseAdminMessagingClientTest {

    private static final PushMessage MESSAGE = new PushMessage(
            "제목", "본문", Map.of("notificationId", "1"), "j1r1T0",
            Duration.ofMinutes(10), 1_775_000_000L, PushMessage.Priority.HIGH);

    private JourneyNotificationProperties properties(long timeoutMs) {
        JourneyNotificationProperties properties = new JourneyNotificationProperties();
        properties.getFcm().setTimeoutMs(timeoutMs);
        return properties;
    }

    @Test
    @DisplayName("설정한 예산을 그대로 SDK 호출에 넘긴다")
    void passesConfiguredBudget() {
        RecordingDispatcher dispatcher = new RecordingDispatcher(PushResult.ok());
        FirebaseAdminMessagingClient client =
                new FirebaseAdminMessagingClient(dispatcher, properties(5000));

        PushResult result = client.send(MESSAGE, "token-a");

        assertThat(result.success()).isTrue();
        assertThat(dispatcher.lastTimeoutMs)
                .as("SDK 기본 타임아웃은 회수 주기보다 길 수 있다 — 우리가 직접 건다")
                .isEqualTo(5000);
        assertThat(dispatcher.lastToken).isEqualTo("token-a");
    }

    @Test
    @DisplayName("provider 오류 코드를 그대로 올려 보낸다")
    void propagatesErrorCode() {
        RecordingDispatcher dispatcher = new RecordingDispatcher(PushResult.failed("UNREGISTERED"));
        FirebaseAdminMessagingClient client =
                new FirebaseAdminMessagingClient(dispatcher, properties(5000));

        PushResult result = client.send(MESSAGE, "token-a");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode())
                .as("분류는 발송기가 한다. 경계는 코드만 전달한다")
                .isEqualTo("UNREGISTERED");
    }

    @Test
    @DisplayName("호출 실패는 일시 실패로 올라간다")
    void callFailureIsTransient() {
        FirebaseAdminMessagingClient client = new FirebaseAdminMessagingClient(
                (message, token, timeoutMs) -> {
                    throw new NotificationSendException("fcm-timeout: 50ms 안에 답이 없다", false);
                },
                properties(50));

        assertThatThrownBy(() -> client.send(MESSAGE, "token-a"))
                .as("실제로 전달됐는지 알 수 없다. 영구로 단정하면 알림이 사라진다")
                .isInstanceOf(NotificationSendException.class)
                .hasMessageContaining("fcm-timeout")
                .hasMessageNotContaining("token-a")
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(false);
    }

    /** 받은 인자를 기록하고 심어 둔 결과를 돌려준다. */
    private static class RecordingDispatcher
            implements FirebaseAdminMessagingClient.MessageDispatcher {

        private final PushResult result;
        private String lastToken;
        private long lastTimeoutMs;

        RecordingDispatcher(PushResult result) {
            this.result = result;
        }

        @Override
        public PushResult dispatch(PushMessage message, String token, long timeoutMs) {
            this.lastToken = token;
            this.lastTimeoutMs = timeoutMs;
            return result;
        }
    }

    @Test
    @DisplayName("시한이 넘으면 SDK future 를 취소한다 — 안 하면 늦게 접수돼 재시도와 겹친다(#451)")
    void timeoutCancelsSdkFuture() {
        com.google.api.core.SettableApiFuture<String> never = com.google.api.core.SettableApiFuture.create();
        FirebaseAdminMessagingClient.SdkDispatcher dispatcher =
                new FirebaseAdminMessagingClient.SdkDispatcher(message -> never);

        assertThatThrownBy(() -> dispatcher.dispatch(MESSAGE, "token-a", 50))
                .isInstanceOf(NotificationSendException.class)
                .hasMessageContaining("fcm-timeout");
        assertThat(never.isCancelled()).isTrue();
    }
}
