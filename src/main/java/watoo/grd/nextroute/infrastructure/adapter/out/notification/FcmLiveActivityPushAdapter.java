package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.exception.NotificationSendException;
import watoo.grd.nextroute.application.journey.port.out.LiveActivityPushPort;
import watoo.grd.nextroute.application.journey.service.SubwayRingPushPayloadFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * FCM 을 거쳐 APNs 로 Live Activity 를 갱신한다.
 *
 * <h2>APNs 어댑터를 따로 만들지 않았다</h2>
 *
 * <p>{@code firebase-admin} 9.10 의 {@code ApnsConfig.Builder} 에 {@code setLiveActivityToken} 이
 * 실재하고, FCM HTTP v1 이 그것을 받아 APNs 로 프록시한다. 그래서 HTTP/2 클라이언트·JWT 서명·
 * {@code .p8} 키 보관·{@code apns-topic} 계산이 전부 필요 없다 — <b>신규 의존성이 없다</b>.
 *
 * <h2>전용 setter 가 없다</h2>
 *
 * <p>{@code Aps.Builder} 가 가진 것은 {@code setAlert}·{@code setBadge}·{@code setSound}·
 * {@code setContentAvailable}·{@code setMutableContent}·{@code setCategory}·{@code setThreadId} 뿐이다.
 * Live Activity 전용 키({@code event}·{@code timestamp}·{@code content-state})는
 * {@code putCustomData} 로 넣는다 — <b>문자열 키라 오타가 컴파일에 안 잡힌다.</b> 그래서 상수로
 * 뽑고 발송 payload 를 시험으로 고정한다.
 *
 * <h2>단위가 섞여 있다</h2>
 *
 * <p>{@code aps.timestamp} 는 epoch <b>초</b>이고 {@code content-state} 안의 시각들은
 * epoch <b>밀리초</b>다. 한쪽을 다른 쪽에 넣으면 1000배 어긋나는데 오류가 아니라 그럴듯하게
 * 틀린 화면으로 나타난다.
 *
 * <p>비동기 API 에 <b>직접 시한</b>을 건다. SDK 기본 타임아웃이 회수 주기보다 길면 아직 발송
 * 중인 행을 회수 루프가 다시 집어 가 같은 화면이 두 번 나간다. 시한 초과는 <b>일시 실패</b>다.
 *
 * <p>예외 문구를 그대로 옮기지 않는다. SDK 메시지에 토큰이 들어 있을 수 있다.
 */
@Slf4j
@Component
// 자격증명이 없으면 뜨지 않는다. FirebaseMessaging 은 sender=fcm 일 때만 만들어지므로, 조건을
// 하나만 걸면 잘못된 조합에서 "빈이 없다"는 엉뚱한 오류로 부팅이 죽는다. 그 조합 자체는
// SubwayRingPushWiringValidator 가 읽을 수 있는 문장으로 막는다.
@ConditionalOnExpression("${journey.subway.ring.push.enabled:false} "
        + "and '${journey.notification.sender:noop}' == 'fcm'")
public class FcmLiveActivityPushAdapter implements LiveActivityPushPort {

    /** {@code aps} 안에 들어가는 Live Activity 전용 키. 오타가 컴파일에 안 잡히므로 상수로 둔다. */
    static final String APS_EVENT = "event";
    static final String APS_TIMESTAMP = "timestamp";
    static final String APS_CONTENT_STATE = "content-state";
    /** epoch 초. 이 시각에 시스템이 화면을 다시 그린다(#309). {@code update} 에만, 규칙은 {@code staleDateSeconds}. */
    static final String APS_STALE_DATE = "stale-date";
    static final String EVENT_UPDATE = "update";

    /** 지금 사용자를 깨우는 갱신이다. 낮추면 잠금화면이 늦게 따라온다. */
    private static final String APNS_PRIORITY_HIGH = "10";

    private final MessageDispatcher dispatcher;
    private final JourneySubwayRingProperties properties;

    @Autowired
    public FcmLiveActivityPushAdapter(FirebaseMessaging messaging,
                                      JourneySubwayRingProperties properties) {
        this(new SdkDispatcher(messaging), properties);
    }

    /** 테스트용 이음매. SDK 없이 시한과 오류 분류를 검증한다. */
    FcmLiveActivityPushAdapter(MessageDispatcher dispatcher,
                               JourneySubwayRingProperties properties) {
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Override
    public PushResult send(String deviceToken, String activityToken, String event,
                           long apsTimestamp, JsonNode contentState) {
        return dispatcher.dispatch(
                message(deviceToken, activityToken, event, apsTimestamp, contentState),
                properties.getPush().getSendTimeoutMs());
    }

    /**
     * 보낼 한 벌을 만든다. <b>이 메서드가 계약의 전부다.</b>
     *
     * <p>패키지 가시성인 이유는 시험이 이 결과를 그대로 들여다보기 때문이다. SDK 객체는 밖에서
     * 못 읽으므로, 대신 여기서 만든 <b>키·단위·중첩 구조</b>를 고정한다.
     */
    static Message message(String deviceToken, String activityToken, String event,
                           long apsTimestamp, JsonNode contentState) {
        return message(deviceToken, activityToken, event, apsTimestamp, contentState, System.currentTimeMillis());
    }

    /** @param nowMillis 보내는 시각. 지난 {@code stale-date} 를 거르는 기준이다 */
    static Message message(String deviceToken, String activityToken, String event,
                           long apsTimestamp, JsonNode contentState, long nowMillis) {
        Aps.Builder apsBuilder = Aps.builder()
                .putCustomData(APS_EVENT, event)
                // epoch 초다. content-state 안의 시각들은 밀리초라는 것을 잊지 말 것.
                .putCustomData(APS_TIMESTAMP, apsTimestamp)
                // {name, props} 두 겹 그대로. props 는 JSON 문자열이다.
                .putCustomData(APS_CONTENT_STATE, toMap(contentState));
        Long staleDate = EVENT_UPDATE.equals(event)
                ? SubwayRingPushPayloadFactory.staleDateSeconds(contentState, nowMillis) : null;
        if (staleDate != null) {
            apsBuilder.putCustomData(APS_STALE_DATE, staleDate);
        }
        Aps aps = apsBuilder.build();

        return Message.builder()
                .setToken(deviceToken)
                .setApnsConfig(ApnsConfig.builder()
                        .putHeader("apns-priority", APNS_PRIORITY_HIGH)
                        // 이 헤더가 없으면 APNs 가 일반 알림으로 다룬다.
                        .putHeader("apns-push-type", "liveactivity")
                        .setAps(aps)
                        .setLiveActivityToken(activityToken)
                        .build())
                .build();
    }

    /**
     * SDK 는 {@code Map} 을 받는다. Jackson 노드를 그대로 넘기면 직렬화가 우리 의도와 달라진다.
     *
     * <p>{@code props} 는 <b>문자열 그대로</b> 남아야 한다 — 여기서 객체로 펴면 앱 디코딩이
     * 실패하고 갱신이 조용히 무시된다.
     */
    private static Map<String, Object> toMap(JsonNode contentState) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (contentState == null) {
            return map;
        }
        contentState.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            map.put(entry.getKey(), value.isTextual() ? value.asText() : value.toString());
        });
        return map;
    }

    /** 한 건을 보내는 자리. 여기 아래에만 SDK 가 있다. */
    interface MessageDispatcher {

        PushResult dispatch(Message message, long timeoutMs);
    }

    private record SdkDispatcher(FirebaseMessaging messaging) implements MessageDispatcher {

        @Override
        public PushResult dispatch(Message message, long timeoutMs) {
            try {
                messaging.sendAsync(message).get(timeoutMs, TimeUnit.MILLISECONDS);
                return PushResult.ok();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NotificationSendException("live-activity-interrupted", false);
            } catch (TimeoutException e) {
                throw new NotificationSendException(
                        "live-activity-timeout: " + timeoutMs + "ms 안에 답이 없다", false);
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
                    "live-activity-call-failed: " + (cause == null ? "unknown"
                            : cause.getClass().getSimpleName()), false);
        }
    }
}
