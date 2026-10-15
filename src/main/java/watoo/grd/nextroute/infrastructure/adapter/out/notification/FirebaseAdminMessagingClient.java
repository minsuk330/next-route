package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import com.google.api.core.ApiFuture;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.exception.NotificationSendException;
import watoo.grd.nextroute.application.journey.port.out.FirebaseMessagingClient;
import watoo.grd.nextroute.application.journey.port.out.PushMessage;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Firebase Admin SDK 로 실제 발송한다. SDK 타입은 이 클래스 밖으로 나가지 않는다.
 *
 * <p>비동기 API 에 <b>직접 시한을 건다</b>. SDK 기본 타임아웃은 relay 의 회수 주기(3분)보다
 * 길 수 있고, 그러면 아직 발송 중인 행을 회수 루프가 "워커가 죽었나 보다" 하고 다시 집어 가
 * 같은 알림이 두 번 나간다. 시한 초과는 <b>일시 실패</b>다 — 실제로 전달됐는지 알 수 없으므로
 * 영구로 단정하면 알림이 조용히 사라진다.
 *
 * <p>예외 문구를 그대로 옮기지 않는다. SDK 메시지에 token 이 들어 있을 수 있고, token 은
 * 로그·지표·예외 어디에도 실으면 안 된다(REQUIREMENTS §6).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "journey.notification", name = "sender", havingValue = "fcm")
public class FirebaseAdminMessagingClient implements FirebaseMessagingClient {

    private static final String APNS_PRIORITY_HIGH = "10";
    private static final String APNS_PRIORITY_NORMAL = "5";

    private final MessageDispatcher dispatcher;
    private final JourneyNotificationProperties properties;

    /**
     * 운영용. <b>{@code @Autowired} 를 지우면 부팅이 죽는다.</b>
     *
     * <p>생성자가 둘이라 Spring 이 주입 대상을 고르지 못한다 — 후보 개수만 세고 인자 없는
     * 생성자로 폴백하는데 그것이 없어 {@code No default constructor found} 로 끝난다.
     * 아래 시험용 생성자의 {@code MessageDispatcher} 는 빈이 아니지만, Spring 은 "그쪽은
     * 주입이 안 되니 이쪽이겠지"를 추론해 주지 않는다.
     */
    @Autowired
    public FirebaseAdminMessagingClient(FirebaseMessaging messaging,
                                        JourneyNotificationProperties properties) {
        this(new SdkDispatcher(messaging::sendAsync), properties);
    }

    /** 테스트용 이음매. SDK 없이 시한과 오류 분류를 검증한다. */
    FirebaseAdminMessagingClient(MessageDispatcher dispatcher,
                                 JourneyNotificationProperties properties) {
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Override
    public PushResult send(PushMessage message, String token) {
        return dispatcher.dispatch(message, token, properties.getFcm().getTimeoutMs());
    }

    /** 한 건을 보내는 자리. 여기 아래에만 SDK 가 있다. */
    interface MessageDispatcher {

        PushResult dispatch(PushMessage message, String token, long timeoutMs);
    }

    /**
     * @param sendAsync 운영은 {@code FirebaseMessaging::sendAsync}. 시험은 끝나지 않는 future 로 취소를 본다
     */
    record SdkDispatcher(Function<Message, ApiFuture<String>> sendAsync) implements MessageDispatcher {

        /**
         * 시한이 넘으면 future 를 {@code cancel(true)} 한다(#451). 안 하면 우리는 실패로 기록하고 재시도하는데 SDK 는 같은
         * 요청을 계속 붙들고(재시도 포함) 있다가 늦게 접수시켜 두 번 울린다. 취소는 SDK 스레드를 인터럽트해 재시도 대기를
         * 끊는다 — 이미 소켓을 읽는 중인 시도 하나는 읽기 시한까지 산다.
         */
        @Override
        public PushResult dispatch(PushMessage message, String token, long timeoutMs) {
            ApiFuture<String> future = sendAsync.apply(toMessage(message, token));
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS);
                return PushResult.ok();
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new NotificationSendException("fcm-interrupted", false);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new NotificationSendException(
                        "fcm-timeout: " + timeoutMs + "ms 안에 답이 없다", false);
            } catch (ExecutionException e) {
                return toResult(e.getCause());
            }
        }

        /**
         * provider 가 준 코드만 꺼낸다. 코드가 없으면(SDK 내부 오류·네트워크) 호출 자체의 실패로
         * 보고 일시 실패로 던진다.
         */
        private static PushResult toResult(Throwable cause) {
            if (cause instanceof FirebaseMessagingException fme) {
                MessagingErrorCode code = fme.getMessagingErrorCode();
                return PushResult.failed(code == null ? "UNKNOWN" : code.name());
            }
            throw new NotificationSendException(
                    "fcm-call-failed: " + (cause == null ? "unknown"
                            : cause.getClass().getSimpleName()), false);
        }

        /**
         * SDK 9.10 은 등록 token targeting 을 deprecated 로 표시하고 FID(Firebase Installation
         * ID) 를 권한다. 옮기려면 앱이 FID 를 올려 주고 {@code user_push_token} 이 그것을 담아야
         * 한다 — 서버만으로는 못 한다. 지금은 token 으로 간다(동작에는 영향 없다).
         */
        @SuppressWarnings("deprecation")
        static Message toMessage(PushMessage message, String token) {
            boolean high = message.priority() == PushMessage.Priority.HIGH;
            return Message.builder()
                    .setToken(token)
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
                            .setNotification(androidNotification(message))
                            .build())
                    .setApnsConfig(ApnsConfig.builder()
                            .putHeader("apns-priority",

                                    high ? APNS_PRIORITY_HIGH : APNS_PRIORITY_NORMAL)
                            // APNs 는 "언제"를 UTC epoch 초로 받는다. 남은 시간을 넣으면 1970년으로
                            // 읽혀 즉시 만료된다.
                            .putHeader("apns-expiration",
                                    String.valueOf(message.apnsExpirationEpochSeconds()))
                            .putHeader("apns-collapse-id", message.collapseKey())
                            .setAps(aps(message))
                            .build())
                    .build();
        }

        /**
         * 피로 정책(#428) 필드는 값이 있을 때만 넣는다 — 정책이 꺼지면 메시지가 이전과 똑같다.
         *
         * <p>조용한 갱신의 소리 없음은 <b>채널</b>이 만든다. FCM v1 은 Android 의 "한 번만 울림"을
         * 노출하지 않아, 같은 tag 로 다시 올리면 기본 채널에선 다시 울린다.
         */
        private static AndroidNotification androidNotification(PushMessage message) {
            AndroidNotification.Builder builder = AndroidNotification.builder()
                    .setTitle(message.title())
                    .setBody(message.body());
            if (message.androidTag() != null) {
                builder.setTag(message.androidTag());
            }
            if (message.androidChannelId() != null) {
                builder.setChannelId(message.androidChannelId());
            }
            return builder.build();
        }

        /** iOS 15+ {@code passive}: 알림 센터에 조용히 놓인다(소리·진동·화면 켜짐 없음). 권한 추가 없음. */
        private static Aps aps(PushMessage message) {
            Aps.Builder builder = Aps.builder();
            if (message.quiet()) {
                builder.putCustomData("interruption-level", "passive");
            }
            return builder.build();
        }
    }
}
