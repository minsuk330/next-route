package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.exception.NotificationSendException;
import watoo.grd.nextroute.application.journey.port.out.FirebaseMessagingClient;
import watoo.grd.nextroute.application.journey.port.out.PushMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Firebase Admin SDK 로 실제 발송한다. SDK 타입은 이 클래스 밖으로 나가지 않는다.
 *
 * <p>세 가지를 여기서 지킨다.
 * <ul>
 *   <li><b>500개 chunk</b> — FCM multicast 의 상한이다. 넘겨 부르면 호출 자체가 거부된다</li>
 *   <li><b>순서 보존</b> — chunk 결과를 입력 token 순서 그대로 잇는다. 어긋나면 발송기가
 *       엉뚱한 token 을 끈다</li>
 *   <li><b>행 전체 예산</b> — chunk 마다 5초가 아니라 <b>합쳐서</b> 5초다. chunk 별 예산이면
 *       token 이 많을 때 합이 회수 주기(3분)를 넘어 같은 알림이 두 번 나간다</li>
 * </ul>
 *
 * <p>예산을 재는 데 {@code Clock} 이 아니라 {@code System.nanoTime()} 을 쓴다. 벽시계는 뒤로
 * 갈 수 있고, 그러면 예산이 음수가 되거나 무한이 된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "journey.notification", name = "sender", havingValue = "fcm")
public class FirebaseAdminMessagingClient implements FirebaseMessagingClient {

    /** FCM multicast 상한. SDK 가 아니라 서버가 거는 제한이다. */
    static final int MAX_TOKENS_PER_CALL = 500;

    private static final String APNS_PRIORITY_HIGH = "10";
    private static final String APNS_PRIORITY_NORMAL = "5";

    private final ChunkSender chunkSender;
    private final JourneyNotificationProperties properties;

    public FirebaseAdminMessagingClient(FirebaseMessaging messaging,
                                        JourneyNotificationProperties properties) {
        this(new SdkChunkSender(messaging), properties);
    }

    /** 테스트용 이음매. SDK 없이 chunk 경계와 예산을 검증한다. */
    FirebaseAdminMessagingClient(ChunkSender chunkSender,
                                 JourneyNotificationProperties properties) {
        this.chunkSender = chunkSender;
        this.properties = properties;
    }

    @Override
    public List<PushResult> send(PushMessage message, List<String> tokens) {
        long budgetMs = properties.getFcm().getTimeoutMs();
        long startNanos = System.nanoTime();

        List<PushResult> results = new ArrayList<>(tokens.size());
        for (int from = 0; from < tokens.size(); from += MAX_TOKENS_PER_CALL) {
            int to = Math.min(from + MAX_TOKENS_PER_CALL, tokens.size());
            long remainingMs = budgetMs - elapsedMillis(startNanos);
            if (remainingMs <= 0) {
                throw new NotificationSendException(
                        "fcm-timeout: 발송 예산 " + budgetMs + "ms 안에 못 끝냈다("
                                + results.size() + "/" + tokens.size() + " 처리)", false);
            }
            results.addAll(chunkSender.send(message, tokens.subList(from, to), remainingMs));
        }
        return results;
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** chunk 하나를 보내는 자리. 여기 아래에만 SDK 가 있다. */
    interface ChunkSender {

        /** @return chunk 와 같은 길이·같은 순서의 결과 */
        List<PushResult> send(PushMessage message, List<String> chunk, long timeoutMs);
    }

    /**
     * SDK 호출.
     *
     * <p>예외 문구를 그대로 옮기지 않는다 — SDK 메시지에 token 이 들어 있을 수 있고, token 은
     * 로그·지표·예외 어디에도 실으면 안 된다(REQUIREMENTS §6).
     */
    private record SdkChunkSender(FirebaseMessaging messaging) implements ChunkSender {

        @Override
        public List<PushResult> send(PushMessage message, List<String> chunk, long timeoutMs) {
            try {
                BatchResponse response = messaging
                        .sendEachForMulticastAsync(toMulticastMessage(message, chunk))
                        .get(timeoutMs, TimeUnit.MILLISECONDS);
                return response.getResponses().stream()
                        .map(SdkChunkSender::toResult)
                        .toList();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NotificationSendException("fcm-interrupted", false);
            } catch (Exception e) {
                // 호출 자체가 실패했다. 무엇인지 모르면 일시 실패로 본다 — 영구로 단정하면
                // 알림이 조용히 사라진다.
                throw new NotificationSendException(
                        "fcm-call-failed: " + e.getClass().getSimpleName(), false);
            }
        }

        private static PushResult toResult(SendResponse response) {
            if (response.isSuccessful()) {
                return PushResult.ok();
            }
            MessagingErrorCode code = response.getException() == null
                    ? null
                    : response.getException().getMessagingErrorCode();
            return PushResult.failed(code == null ? "UNKNOWN" : code.name());
        }

        /**
         * SDK 9.10 은 등록 token targeting 을 deprecated 로 표시하고 FID(Firebase Installation
         * ID) 를 권한다. 옮기려면 앱이 FID 를 올려 주고 {@code user_push_token} 이 그것을 담아야
         * 한다 — 서버만으로는 못 한다. 지금은 token 으로 간다(동작에는 영향 없다).
         */
        @SuppressWarnings("deprecation")
        private static MulticastMessage toMulticastMessage(PushMessage message,
                                                           List<String> chunk) {
            boolean high = message.priority() == PushMessage.Priority.HIGH;
            MulticastMessage.Builder builder = MulticastMessage.builder();
            chunk.forEach(builder::addToken);
            return builder
                    .putAllData(message.data())
                    .setNotification(Notification.builder()
                            .setTitle(message.title())
                            .setBody(message.body())
                            .build())
                    .setAndroidConfig(AndroidConfig.builder()
                            .setPriority(high
                                    ? AndroidConfig.Priority.HIGH
                                    : AndroidConfig.Priority.NORMAL)
                            .setCollapseKey(message.collapseKey())
                            // Android 는 "앞으로 몇 초"를 받는다.
                            .setTtl(Math.max(0, message.androidTtl().toMillis()))
                            .setNotification(AndroidNotification.builder()
                                    .setTitle(message.title())
                                    .setBody(message.body())
                                    .build())
                            .build())
                    .setApnsConfig(ApnsConfig.builder()
                            .putHeader("apns-priority",
                                    high ? APNS_PRIORITY_HIGH : APNS_PRIORITY_NORMAL)
                            // APNs 는 "언제"를 UTC epoch 초로 받는다. 남은 시간을 넣으면 1970년으로
                            // 읽혀 즉시 만료된다.
                            .putHeader("apns-expiration",
                                    String.valueOf(message.apnsExpirationEpochSeconds()))
                            .putHeader("apns-collapse-id", message.collapseKey())
                            .build())
                    .build();
        }
    }
}
