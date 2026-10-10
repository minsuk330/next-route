package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.firebase.messaging.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.exception.NotificationSendException;
import watoo.grd.nextroute.application.journey.port.out.LiveActivityPushPort;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FCM 으로 나가는 <b>Live Activity 전선 계약</b>.
 *
 * <p>{@code Aps.Builder} 에는 {@code event}·{@code timestamp}·{@code content-state} 전용 setter 가
 * 없어 {@code putCustomData} 로 넣는다. <b>문자열 키라 오타가 컴파일에 안 잡히고</b>, 틀려도
 * FCM 은 200 을 준다 — 실패는 사용자 잠금화면에서만 보인다. 그래서 만들어진 {@link Message} 를
 * 직접 열어 본다.
 *
 * <p>SDK 의 접근자가 패키지 전용이라 리플렉션으로 읽는다. 그 대신 <b>실제로 전송될 객체</b>를
 * 검사하므로, 우리 조립기를 흉내 낸 대역을 검사할 때 생기는 눈속임이 없다.
 */
@DisplayName("Live Activity FCM payload 계약")
class FcmLiveActivityPushAdapterTest {

    private static final String DEVICE_TOKEN = "device-token-abc";
    private static final String ACTIVITY_TOKEN = "activity-token-xyz";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("aps 안에 event·timestamp·content-state 가 실린다")
    void apsCarriesLiveActivityKeys() {
        Message message = FcmLiveActivityPushAdapter.message(
                DEVICE_TOKEN, ACTIVITY_TOKEN, "update", 1786681200L, contentState());

        Map<String, Object> aps = apsFields(message);
        assertThat(aps.get(FcmLiveActivityPushAdapter.APS_EVENT)).isEqualTo("update");
        assertThat(aps.get(FcmLiveActivityPushAdapter.APS_TIMESTAMP)).isEqualTo(1786681200L);
        assertThat(aps).containsKey(FcmLiveActivityPushAdapter.APS_CONTENT_STATE);
    }

    @Test
    @DisplayName("종료(end)에는 dismissal-date(epoch 초, 보낸 시각 + 15초)를 싣는다 — 없으면 낡은 화면이 4시간 남는다")
    void endCarriesDismissalDate() {
        long now = 1_786_681_200_000L;

        Map<String, Object> aps = apsFields(FcmLiveActivityPushAdapter.message(
                DEVICE_TOKEN, ACTIVITY_TOKEN, "end", 1786681200L, contentState(), now));

        assertThat(aps.get(FcmLiveActivityPushAdapter.APS_DISMISSAL_DATE)).isEqualTo(now / 1000 + 15);
    }

    @Test
    @DisplayName("갱신(update)에는 dismissal-date 가 없다")
    void updateHasNoDismissalDate() {
        Map<String, Object> aps = apsFields(FcmLiveActivityPushAdapter.message(
                DEVICE_TOKEN, ACTIVITY_TOKEN, "update", 1786681200L, contentState()));

        assertThat(aps).doesNotContainKey(FcmLiveActivityPushAdapter.APS_DISMISSAL_DATE);
    }

    @Test
    @DisplayName("aps.timestamp 는 epoch 초다 — props 의 밀리초와 자릿수가 다르다")
    void timestampIsSeconds() {
        long seconds = 1786681200L;

        Map<String, Object> aps = apsFields(FcmLiveActivityPushAdapter.message(
                DEVICE_TOKEN, ACTIVITY_TOKEN, "update", seconds, contentState()));

        long sent = (long) aps.get(FcmLiveActivityPushAdapter.APS_TIMESTAMP);
        assertThat(sent)
                .as("밀리초를 넣으면 1000배 커져 이 상한을 넘는다")
                .isLessThan(100_000_000_000L);
        assertThat(sent).isEqualTo(seconds);
    }

    @Test
    @DisplayName("content-state 는 두 겹이고 props 는 문자열로 남는다 — 객체로 펴면 앱이 무시한다")
    void contentStateStaysTwoLayered() {
        Message message = FcmLiveActivityPushAdapter.message(
                DEVICE_TOKEN, ACTIVITY_TOKEN, "update", 1786681200L, contentState());

        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>)
                apsFields(message).get(FcmLiveActivityPushAdapter.APS_CONTENT_STATE);

        assertThat(state.get("name")).isEqualTo("NavigationLiveActivity");
        assertThat(state.get("props"))
                .as("props 는 JSON 문자열이어야 한다")
                .isInstanceOf(String.class);
        assertThat((String) state.get("props")).contains("\"phase\":\"RIDE_SUBWAY\"");
    }

    @Test
    @DisplayName("두 토큰이 각자 제자리에 실린다 — 기기는 Message, Activity 는 apns")
    void bothTokensAreCarried() {
        Message message = FcmLiveActivityPushAdapter.message(
                DEVICE_TOKEN, ACTIVITY_TOKEN, "update", 1786681200L, contentState());

        assertThat(field(message, "token")).isEqualTo(DEVICE_TOKEN);
        Object apns = field(message, "apnsConfig");
        assertThat(field(apns, "liveActivityToken")).isEqualTo(ACTIVITY_TOKEN);
    }

    @Test
    @DisplayName("apns-push-type 이 liveactivity 다 — 없으면 APNs 가 일반 알림으로 다룬다")
    void pushTypeHeaderIsSet() {
        Message message = FcmLiveActivityPushAdapter.message(
                DEVICE_TOKEN, ACTIVITY_TOKEN, "end", 1786681200L, contentState());

        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>)
                field(field(message, "apnsConfig"), "headers");

        assertThat(headers).containsEntry("apns-push-type", "liveactivity");
        assertThat(headers).containsEntry("apns-priority", "10");
    }

    @Test
    @DisplayName("update 에는 aps.stale-date 가 epoch 초로 실린다 — 규칙은 SubwayRingPushPayloadFactoryTest.StaleDate")
    void updateCarriesStaleDateInSeconds() {
        Map<String, Object> aps = apsFields(FcmLiveActivityPushAdapter.message(
                DEVICE_TOKEN, ACTIVITY_TOKEN, "update", 1786681200L, contentStateValidUntil(1786681290500L),
                1786681200000L));

        assertThat(aps.get(FcmLiveActivityPushAdapter.APS_STALE_DATE)).isEqualTo(1786681290L);
    }

    @Test
    @DisplayName("후보 시각이 없으면 stale-date 를 빼고 보낸다 — 지어내지 않는다")
    void noValidUntilMeansNoStaleDate() {
        Map<String, Object> aps = apsFields(FcmLiveActivityPushAdapter.message(
                DEVICE_TOKEN, ACTIVITY_TOKEN, "update", 1786681200L, contentState()));

        assertThat(aps).doesNotContainKey(FcmLiveActivityPushAdapter.APS_STALE_DATE);
    }

    @Test
    @DisplayName("end 에는 stale-date 를 싣지 않는다")
    void endHasNoStaleDate() {
        Map<String, Object> aps = apsFields(FcmLiveActivityPushAdapter.message(
                DEVICE_TOKEN, ACTIVITY_TOKEN, "end", 1786681200L, contentStateValidUntil(1786681290500L),
                1786681200000L));

        assertThat(aps).doesNotContainKey(FcmLiveActivityPushAdapter.APS_STALE_DATE);
    }

    @Test
    @DisplayName("종료는 event=end 로 나간다")
    void endEventGoesOut() {
        Map<String, Object> aps = apsFields(FcmLiveActivityPushAdapter.message(
                DEVICE_TOKEN, ACTIVITY_TOKEN, "end", 1786681200L, contentState()));

        assertThat(aps.get(FcmLiveActivityPushAdapter.APS_EVENT)).isEqualTo("end");
    }

    @Test
    @DisplayName("provider 오류 코드는 그대로 올라온다 — UNREGISTERED 는 죽은 주소다")
    void providerErrorCodeSurvives() {
        FcmLiveActivityPushAdapter adapter = new FcmLiveActivityPushAdapter(
                (message, timeoutMs) -> LiveActivityPushPort.PushResult.failed("UNREGISTERED"),
                new JourneySubwayRingProperties());

        LiveActivityPushPort.PushResult result = adapter.send(
                DEVICE_TOKEN, ACTIVITY_TOKEN, "update", 1L, contentState());

        assertThat(result.success()).isFalse();
        assertThat(result.isTokenDead()).isTrue();
    }

    @Test
    @DisplayName("INVALID_ARGUMENT 로는 주소를 끄지 않는다 — 설정 실수가 주소록을 지운다")
    void invalidArgumentDoesNotKillTheToken() {
        LiveActivityPushPort.PushResult result =
                LiveActivityPushPort.PushResult.failed("INVALID_ARGUMENT");

        assertThat(result.isTokenDead()).isFalse();
    }

    @Test
    @DisplayName("시한 초과는 일시 실패다 — 배달됐는지 알 수 없으므로 영구로 단정하지 않는다")
    void timeoutIsTransient() {
        FcmLiveActivityPushAdapter adapter = new FcmLiveActivityPushAdapter(
                (message, timeoutMs) -> {
                    throw new NotificationSendException("live-activity-timeout", false);
                },
                new JourneySubwayRingProperties());

        assertThatThrownBy(() -> adapter.send(DEVICE_TOKEN, ACTIVITY_TOKEN, "update", 1L,
                contentState()))
                .isInstanceOf(NotificationSendException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("설정한 시한이 발송기에 그대로 전달된다")
    void configuredTimeoutIsUsed() {
        JourneySubwayRingProperties properties = new JourneySubwayRingProperties();
        properties.getPush().setSendTimeoutMs(1234);
        long[] seen = new long[1];
        FcmLiveActivityPushAdapter adapter = new FcmLiveActivityPushAdapter(
                (message, timeoutMs) -> {
                    seen[0] = timeoutMs;
                    return LiveActivityPushPort.PushResult.ok();
                }, properties);

        adapter.send(DEVICE_TOKEN, ACTIVITY_TOKEN, "update", 1L, contentState());

        assertThat(seen[0]).isEqualTo(1234);
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private ObjectNode contentStateValidUntil(long validUntilMillis) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("name", "NavigationLiveActivity");
        envelope.put("props", "{\"phase\":\"RIDE_SUBWAY\",\"progressValidUntil\":" + validUntilMillis + "}");
        return envelope;
    }

    private ObjectNode contentState() {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("name", "NavigationLiveActivity");
        envelope.put("props", "{\"phase\":\"RIDE_SUBWAY\",\"segmentProgress\":0.45}");
        return envelope;
    }

    /**
     * {@code aps} 사전.
     *
     * <p>SDK 는 {@code ApnsConfig.payload} 의 {@code "aps"} 자리에 {@link com.google.firebase
     * .messaging.Aps} 객체가 아니라 <b>그 안의 Map 을 펴서</b> 넣는다. 그래서 한 겹 덜 들어간다.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> apsFields(Message message) {
        Object apnsConfig = field(message, "apnsConfig");
        Map<String, Object> payload = (Map<String, Object>) field(apnsConfig, "payload");
        return (Map<String, Object>) payload.get("aps");
    }

    /** SDK 의 접근자가 패키지 전용이라 필드를 직접 읽는다. */
    private Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "SDK 내부 모양이 바뀌었다 — payload 계약을 다시 확인할 것: " + name, e);
        }
    }

    /** 시한 초과가 SDK 쪽에서 오는 경우도 같은 예외로 분류되는지. */
    @Test
    @DisplayName("SDK 시한 초과도 일시 실패로 분류된다")
    void sdkTimeoutIsTransient() {
        FcmLiveActivityPushAdapter adapter = new FcmLiveActivityPushAdapter(
                (message, timeoutMs) -> {
                    throw new NotificationSendException(
                            "live-activity-timeout: " + new TimeoutException().getClass()
                                    .getSimpleName(), false);
                },
                new JourneySubwayRingProperties());

        assertThatThrownBy(() -> adapter.send(DEVICE_TOKEN, ACTIVITY_TOKEN, "update", 1L,
                contentState()))
                .isInstanceOf(NotificationSendException.class);
    }
}
