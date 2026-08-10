package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.port.out.PushMessage;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * outbox 행 하나를 push 한 벌로 옮긴다.
 *
 * <p><b>유효시간·collapse key·우선순위·data 허용 목록·문구가 존재하는 유일한 자리다.</b> ⑥이
 * payload 계약과 deep link 를 정할 때 여기만 고치면 된다.
 *
 * <p>문구는 임시다. 운영 발송은 ⑤·⑥ 이 들어온 뒤에 켠다 — 지금 켜면 이 문구가 그대로 굳는다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "journey.notification", name = "sender", havingValue = "fcm")
public class FcmMessageFactory {

    /** APNs {@code apns-collapse-id} 의 하드 제한. 넘기면 그 메시지가 거부된다. */
    static final int COLLAPSE_KEY_MAX_BYTES = 64;

    private final JourneyNotificationProperties properties;

    /**
     * @param now 발송 시점. TTL 두 값이 여기서 파생한다
     */
    public PushMessage from(NotificationSendCommand command, Instant now) {
        Instant providerExpiresAt = providerExpiresAt(command, now);
        return new PushMessage(
                title(command.checkpoint()),
                body(command.checkpoint()),
                data(command),
                collapseKey(command),
                // Android 는 "앞으로 몇 초", APNs 는 "언제(UTC epoch 초)". 같은 숫자를 양쪽에
                // 넣으면 iOS 알림이 1970년으로 읽혀 즉시 만료된다.
                Duration.between(now, providerExpiresAt),
                providerExpiresAt.getEpochSecond(),
                // 환승 위험은 즉시 행동이 필요한 알림이다(TECHNICAL_DECISIONS §2 LOCKED).
                PushMessage.Priority.HIGH);
    }

    /**
     * provider 가 메시지를 들고 있을 시각.
     *
     * <p>{@code min(만료, 지금 + 상한)} 이되 과거로는 내려가지 않는다. MISSED 는 "다음 차 도착이
     * 이미 과거"라 만료가 지금보다 앞설 수 있는데, 그때 음수 TTL 을 넣으면 SDK 가 거부한다.
     */
    private Instant providerExpiresAt(NotificationSendCommand command, Instant now) {
        Instant capped = now.plus(properties.getFcm().ttlMax());
        Instant expiresAt = command.expiresAt().isBefore(capped) ? command.expiresAt() : capped;
        return expiresAt.isBefore(now) ? now : expiresAt;
    }

    /**
     * 대기 중인 옛 알림을 새 알림이 덮게 하는 열쇠.
     *
     * <p><b>체크포인트를 넣지 않는다.</b> 넣으면 {@code AT_RISK} 와 {@code MISSED} 가 서로 안
     * 덮는다 — 덮는 것이 목적이다.
     *
     * <p>논리 키({@code 7:1:TRANSFER:0:TRANSFER_RISK_AT_RISK})는 최대 255자라 그대로 못 쓴다.
     * 여기 형식은 {@code Long.MAX_VALUE} 를 넣어도 45자를 넘지 않아 64바이트 제한 안에 있다.
     */
    private String collapseKey(NotificationSendCommand command) {
        return "j" + command.journeyId()
                + "r" + command.routeRevision()
                + command.scopeType().name().charAt(0)
                + command.scopeId();
    }

    /**
     * push 에 싣는 값 — <b>식별자뿐</b>이다.
     *
     * <p>outbox {@code payload} 에는 {@code status}·{@code transferSlackSeconds} 같은 상태값이
     * 들어 있다. 그것을 그대로 평탄화하면 "push 는 상태 저장소가 아니다"(REQUIREMENTS §4.5)를
     * 정면으로 어긴다 — 늦게 도착한 알림이 낡은 숫자를 사용자에게 보여 준다. 앱은 이 식별자로
     * 최신 상태를 REST 로 다시 조회한다.
     *
     * <p>FCM 예약 키({@code from}, {@code google*}, {@code gcm*}, {@code notification})와 겹치는
     * 이름은 쓰지 않는다. 값은 전부 문자열이어야 한다(FCM {@code data} 계약).
     */
    private Map<String, String> data(NotificationSendCommand command) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("notificationId", String.valueOf(command.notificationId()));
        data.put("journeyId", String.valueOf(command.journeyId()));
        data.put("routeRevision", String.valueOf(command.routeRevision()));
        data.put("scopeType", command.scopeType().name());
        data.put("scopeId", String.valueOf(command.scopeId()));
        data.put("checkpoint", command.checkpoint().name());
        data.put("commandVersion", String.valueOf(command.commandVersion()));
        data.put("evaluationVersion", String.valueOf(command.evaluationVersion()));
        return data;
    }

    /** 임시 문구. ⑥ 이 최종 문구와 deep link 를 확정한다. */
    private String title(JourneyCheckpoint checkpoint) {
        return switch (checkpoint) {
            case TRANSFER_RISK_AT_RISK -> "환승 시간이 빠듯해요";
            case TRANSFER_RISK_MISSED -> "환승을 놓쳤어요";
            case TRANSFER_TARGET_APPROACHING -> "갈아탈 차가 곧 도착해요";
        };
    }

    private String body(JourneyCheckpoint checkpoint) {
        return switch (checkpoint) {
            case TRANSFER_RISK_AT_RISK -> "지금 이동하면 다음 차를 탈 수 있어요.";
            case TRANSFER_RISK_MISSED -> "앱에서 새 안내를 확인하세요.";
            case TRANSFER_TARGET_APPROACHING -> "탑승 준비를 하세요.";
        };
    }
}
