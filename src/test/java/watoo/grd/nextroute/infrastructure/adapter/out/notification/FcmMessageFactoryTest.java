package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.port.out.PushMessage;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * outbox 행이 push 한 벌이 되는 자리.
 *
 * <p>두 가지가 조용히 망가진다. 유효시간의 <b>단위</b>를 헷갈리면 iOS 알림이 즉시 만료되고,
 * {@code data} 에 상태값을 실으면 늦게 도착한 알림이 낡은 숫자를 보여 준다. 둘 다 로그에 안 남는다.
 */
@DisplayName("FCM 메시지 조립")
class FcmMessageFactoryTest {

    private static final Instant NOW = Instant.parse("2026-08-10T03:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JourneyNotificationProperties properties = new JourneyNotificationProperties();
    private final FcmMessageFactory factory = new FcmMessageFactory(properties);

    private NotificationSendCommand command(Instant expiresAt) {
        return new NotificationSendCommand(7L, 42L, 11L, 3,
                JourneyNotificationScope.TRANSFER, 0, JourneyCheckpoint.TRANSFER_RISK_AT_RISK,
                NotificationChannel.PUSH, 5L, 9L,
                MAPPER.createObjectNode().put("status", "AT_RISK").put("transferSlackSeconds", 40),
                expiresAt);
    }

    @Test
    @DisplayName("Android 는 남은 시간, APNs 는 만료 시각의 epoch 초다")
    void ttlIsDerivedInTwoUnits() {
        Instant expiresAt = NOW.plusSeconds(600);

        PushMessage message = factory.from(command(expiresAt), NOW);

        assertThat(message.androidTtl()).isEqualTo(Duration.ofSeconds(600));
        assertThat(message.apnsExpirationEpochSeconds())
                .as("남은 시간 600 을 apns-expiration 에 넣으면 1970-01-01 00:10 으로 읽혀 즉시 만료된다")
                .isEqualTo(expiresAt.getEpochSecond())
                .isNotEqualTo(message.androidTtl().toSeconds());
    }

    @Test
    @DisplayName("만료가 상한보다 멀면 상한으로 자른다")
    void ttlIsCappedByMax() {
        PushMessage message = factory.from(command(NOW.plusSeconds(99_999)), NOW);

        assertThat(message.androidTtl()).isEqualTo(properties.getFcm().ttlMax());
        assertThat(message.apnsExpirationEpochSeconds())
                .isEqualTo(NOW.plus(properties.getFcm().ttlMax()).getEpochSecond());
    }

    @Test
    @DisplayName("만료가 이미 과거면 0 과 현재로 내려앉는다")
    void pastExpiryClampsToNow() {
        PushMessage message = factory.from(command(NOW.minusSeconds(30)), NOW);

        assertThat(message.androidTtl())
                .as("음수 TTL 은 SDK 가 거부한다")
                .isZero();
        assertThat(message.apnsExpirationEpochSeconds()).isEqualTo(NOW.getEpochSecond());
    }

    @Test
    @DisplayName("collapse key 에 체크포인트가 없다 — 놓침이 위험을 덮어야 한다")
    void collapseKeyIgnoresCheckpoint() {
        NotificationSendCommand atRisk = command(NOW.plusSeconds(600));
        NotificationSendCommand missed = new NotificationSendCommand(
                atRisk.notificationId(), atRisk.userId(), atRisk.journeyId(),
                atRisk.routeRevision(), atRisk.scopeType(), atRisk.scopeId(),
                JourneyCheckpoint.TRANSFER_RISK_MISSED, atRisk.channel(),
                atRisk.commandVersion(), atRisk.evaluationVersion(),
                atRisk.payload(), atRisk.expiresAt());

        assertThat(factory.from(atRisk, NOW).collapseKey())
                .isEqualTo("j11r3T0")
                .isEqualTo(factory.from(missed, NOW).collapseKey());
    }

    @Test
    @DisplayName("collapse key 는 어떤 값이 와도 64바이트를 넘지 않는다")
    void collapseKeyFitsApnsLimit() {
        NotificationSendCommand extreme = new NotificationSendCommand(
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE,
                JourneyNotificationScope.JOURNEY, Integer.MAX_VALUE,
                JourneyCheckpoint.TRANSFER_RISK_MISSED, NotificationChannel.PUSH,
                Long.MAX_VALUE, Long.MAX_VALUE, null, NOW.plusSeconds(600));

        assertThat(factory.from(extreme, NOW).collapseKey()
                .getBytes(StandardCharsets.UTF_8).length)
                .as("넘기면 apns-collapse-id 제한에 걸려 그 메시지가 거부된다")
                .isLessThanOrEqualTo(FcmMessageFactory.COLLAPSE_KEY_MAX_BYTES);
    }

    @Test
    @DisplayName("data 는 식별자만 담는다 — 상태값은 싣지 않는다")
    void dataCarriesIdentifiersOnly() {
        PushMessage message = factory.from(command(NOW.plusSeconds(600)), NOW);

        assertThat(message.data()).containsOnlyKeys("notificationId", "journeyId",
                "routeRevision", "scopeType", "scopeId", "checkpoint", "commandVersion",
                "evaluationVersion", "messageKey", "deepLink");
        assertThat(message.data())
                .as("늦게 도착한 알림이 낡은 숫자를 보여 준다(REQUIREMENTS §4.5)")
                .doesNotContainKeys("status", "transferSlackSeconds", "nextBusArrivalAt");
        assertThat(message.data()).containsEntry("notificationId", "7")
                .containsEntry("journeyId", "11")
                .containsEntry("scopeType", "TRANSFER")
                .containsEntry("checkpoint", "TRANSFER_RISK_AT_RISK")
                .containsEntry("commandVersion", "5")
                .containsEntry("evaluationVersion", "9");
    }

    @Test
    @DisplayName("data 키가 FCM 예약어와 겹치지 않는다")
    void dataKeysAvoidReservedNames() {
        PushMessage message = factory.from(command(NOW.plusSeconds(600)), NOW);

        assertThat(message.data().keySet())
                .noneMatch(key -> key.equals("from") || key.equals("notification")
                        || key.startsWith("google") || key.startsWith("gcm"));
    }

    @Test
    @DisplayName("payload 가 null 이거나 객체가 아니어도 터지지 않는다")
    void payloadShapeDoesNotMatter() {
        NotificationSendCommand base = command(NOW.plusSeconds(600));
        NotificationSendCommand nullPayload = withPayload(base, null);
        NotificationSendCommand scalarPayload = withPayload(base, MAPPER.getNodeFactory()
                .numberNode(1));

        assertThatCode(() -> {
            factory.from(nullPayload, NOW);
            factory.from(scalarPayload, NOW);
        }).as("payload 는 조립에 쓰지 않는다 — 모양이 어떻든 상관없어야 한다")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("체크포인트마다 문구가 다르고 우선순위는 즉시 전달이다")
    void wordingAndPriority() {
        PushMessage message = factory.from(command(NOW.plusSeconds(600)), NOW);

        assertThat(message.title()).isNotBlank();
        assertThat(message.body()).isNotBlank();
        assertThat(message.priority()).isEqualTo(PushMessage.Priority.HIGH);
    }

    @Test
    @DisplayName("체크포인트 셋이 각각 다른 문구와 문구 키를 갖는다")
    void everyCheckpointHasItsOwnWording() {
        List<PushMessage> messages = Arrays.stream(JourneyCheckpoint.values())
                .map(checkpoint -> factory.from(
                        withCheckpoint(command(NOW.plusSeconds(600)), checkpoint), NOW))
                .toList();

        assertThat(messages).extracting(PushMessage::title).doesNotHaveDuplicates();
        assertThat(messages).extracting(PushMessage::body).doesNotHaveDuplicates();
        assertThat(messages).extracting(m -> m.data().get("messageKey"))
                .as("앱이 이 키로 자기 문구를 고를 수 있어야 한다")
                .doesNotHaveDuplicates()
                // 접두어는 "무엇에 대한 알림인가"를 가른다. 환승(journey.transfer.*) 말고
                // 재탐색(journey.reroute.*)이 생겼으므로 공통 접두어까지만 본다.
                .allSatisfy(key -> assertThat(key).startsWith("journey."));
    }

    @Test
    @DisplayName("deep link 는 서버가 완성해서 준다 — 앱이 조립하지 않는다")
    void deepLinkIsFullyFormed() {
        PushMessage message = factory.from(command(NOW.plusSeconds(600)), NOW);

        assertThat(message.data().get("deepLink"))
                .as("이 글자가 앱 등록 스킴과 다르면 OS 가 알림을 열 곳을 못 찾는다")
                .isEqualTo("nextroute://journey/11?alertId=7&scope=TRANSFER&scopeId=0");
    }

    @Test
    @DisplayName("deep link 의 alertId 는 알림 행 id 와 같다")
    void deepLinkAlertIdMatchesNotificationId() {
        PushMessage message = factory.from(command(NOW.plusSeconds(600)), NOW);

        assertThat(message.data().get("deepLink"))
                .contains("alertId=" + message.data().get("notificationId"));
    }

    @Test
    @DisplayName("deep link 에 사용자 식별자를 싣지 않는다 — 알림은 잠금화면에 보인다")
    void deepLinkCarriesNoUserIdentity() {
        PushMessage message = factory.from(command(NOW.plusSeconds(600)), NOW);

        assertThat(message.data().get("deepLink")).doesNotContain("42");
    }

    private NotificationSendCommand withCheckpoint(NotificationSendCommand base,
                                                   JourneyCheckpoint checkpoint) {
        return new NotificationSendCommand(base.notificationId(), base.userId(), base.journeyId(),
                base.routeRevision(), base.scopeType(), base.scopeId(), checkpoint,
                base.channel(), base.commandVersion(), base.evaluationVersion(),
                base.payload(), base.expiresAt());
    }

    private NotificationSendCommand withPayload(NotificationSendCommand base,
                                                com.fasterxml.jackson.databind.JsonNode payload) {
        return new NotificationSendCommand(base.notificationId(), base.userId(), base.journeyId(),
                base.routeRevision(), base.scopeType(), base.scopeId(), base.checkpoint(),
                base.channel(), base.commandVersion(), base.evaluationVersion(),
                payload, base.expiresAt());
    }
}
