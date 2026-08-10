package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.exception.NotificationSendException;
import watoo.grd.nextroute.application.journey.port.out.FirebaseMessagingClient.PushResult;
import watoo.grd.nextroute.application.journey.port.out.PushMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * chunk 경계와 예산. SDK 없이 검증한다.
 *
 * <p>순서가 이 테스트의 핵심이다. chunk 결과를 잘못 이으면 발송기가 <b>엉뚱한 token 을 끈다</b> —
 * 사용자는 알림을 못 받는데 어디에도 오류가 안 남는다.
 */
@DisplayName("FCM 호출은 500개씩 나누고 순서를 지킨다")
class FirebaseAdminMessagingClientTest {

    private static final PushMessage MESSAGE = new PushMessage(
            "제목", "본문", Map.of("notificationId", "1"), "j1r1T0",
            Duration.ofMinutes(10), 1_775_000_000L, PushMessage.Priority.HIGH);

    private JourneyNotificationProperties properties(long timeoutMs) {
        JourneyNotificationProperties properties = new JourneyNotificationProperties();
        properties.getFcm().setTimeoutMs(timeoutMs);
        return properties;
    }

    private List<String> tokens(int count) {
        return IntStream.range(0, count).mapToObj(i -> "token-" + i).toList();
    }

    @Test
    @DisplayName("500개 이하면 한 번만 부른다")
    void singleCallUpTo500() {
        RecordingChunkSender chunkSender = new RecordingChunkSender();
        FirebaseAdminMessagingClient client =
                new FirebaseAdminMessagingClient(chunkSender, properties(5000));

        List<PushResult> results = client.send(MESSAGE, tokens(500));

        assertThat(chunkSender.chunkSizes).containsExactly(500);
        assertThat(results).hasSize(500);
    }

    @Test
    @DisplayName("501개면 두 번 부르고 결과는 입력 순서 그대로 이어진다")
    void splitsAtChunkBoundaryKeepingOrder() {
        RecordingChunkSender chunkSender = new RecordingChunkSender();
        FirebaseAdminMessagingClient client =
                new FirebaseAdminMessagingClient(chunkSender, properties(5000));
        List<String> tokens = tokens(501);

        List<PushResult> results = client.send(MESSAGE, tokens);

        assertThat(chunkSender.chunkSizes).containsExactly(500, 1);
        assertThat(results).hasSize(501);
        // 가짜 sender 가 결과의 errorCode 자리에 받은 token 을 그대로 넣는다. 순서가 어긋나면
        // 여기서 드러난다.
        assertThat(results.stream().map(PushResult::errorCode).toList())
                .as("어긋나면 발송기가 엉뚱한 token 을 끈다")
                .isEqualTo(tokens);
    }

    @Test
    @DisplayName("예산은 행 전체 기준이다 — 남은 시간이 줄어든 채 다음 chunk 로 넘어간다")
    void budgetIsSharedAcrossChunks() {
        RecordingChunkSender chunkSender = new RecordingChunkSender(30);
        FirebaseAdminMessagingClient client =
                new FirebaseAdminMessagingClient(chunkSender, properties(5000));

        client.send(MESSAGE, tokens(501));

        assertThat(chunkSender.timeouts).hasSize(2);
        assertThat(chunkSender.timeouts.get(1))
                .as("chunk 마다 5초면 합이 회수 주기를 넘어 같은 알림이 두 번 나간다")
                .isLessThan(chunkSender.timeouts.get(0));
    }

    @Test
    @DisplayName("예산을 다 쓰면 일시 실패다 — 남은 chunk 는 재시도가 가져간다")
    void exhaustedBudgetIsTransient() {
        RecordingChunkSender chunkSender = new RecordingChunkSender(80);
        FirebaseAdminMessagingClient client =
                new FirebaseAdminMessagingClient(chunkSender, properties(50));

        assertThatThrownBy(() -> client.send(MESSAGE, tokens(501)))
                .isInstanceOf(NotificationSendException.class)
                .hasMessageContaining("fcm-timeout")
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(false);
    }

    @Test
    @DisplayName("예외 문구에 token 이 들어가지 않는다")
    void timeoutMessageCarriesNoToken() {
        RecordingChunkSender chunkSender = new RecordingChunkSender(80);
        FirebaseAdminMessagingClient client =
                new FirebaseAdminMessagingClient(chunkSender, properties(50));

        assertThatThrownBy(() -> client.send(MESSAGE, tokens(501)))
                .hasMessageNotContaining("token-");
    }

    /** 받은 chunk 를 기록하고, 결과의 {@code errorCode} 자리에 token 을 되돌려 준다. */
    private static class RecordingChunkSender implements FirebaseAdminMessagingClient.ChunkSender {

        private final List<Integer> chunkSizes = new ArrayList<>();
        private final List<Long> timeouts = new ArrayList<>();
        private final long delayMillis;

        RecordingChunkSender() {
            this(0);
        }

        RecordingChunkSender(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        @Override
        public List<PushResult> send(PushMessage message, List<String> chunk, long timeoutMs) {
            chunkSizes.add(chunk.size());
            timeouts.add(timeoutMs);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return chunk.stream().map(token -> new PushResult(false, token)).toList();
        }
    }
}
