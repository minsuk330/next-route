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
 * <p>문구는 ⑥ 이 확정했다. 운영 발송을 켠 뒤에 바꾸면 사용자 눈에 그대로 보이므로, 켜기 전에
 * 굳히는 것이 ⑥ 의 목적이었다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "journey.notification", name = "sender", havingValue = "fcm")
public class FcmMessageFactory {

    /** APNs {@code apns-collapse-id} 의 하드 제한. 넘기면 그 메시지가 거부된다. */
    static final int COLLAPSE_KEY_MAX_BYTES = 64;

    /**
     * 앱이 등록한 URL 스킴. 이 글자가 앱 등록값과 다르면 OS 가 알림을 열 곳을 못 찾는다.
     *
     * <p>https 유니버설 링크가 아니라 커스텀 스킴인 이유 — 유니버설 링크는 도메인에 iOS
     * {@code apple-app-site-association} 과 Android {@code assetlinks.json} 을 먼저 배포해야
     * 동작한다. 그 설정이 생기면 여기만 바꾼다.
     */
    static final String DEEP_LINK_SCHEME = "nextroute";

    private final JourneyNotificationProperties properties;

    /**
     * @param now 발송 시점. TTL 두 값이 여기서 파생한다
     */
    public PushMessage from(NotificationSendCommand command, Instant now) {
        Instant providerExpiresAt = providerExpiresAt(command, now);
        return new PushMessage(
                title(command.checkpoint()),
                body(command),
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
        data.put("messageKey", messageKey(command.checkpoint()));
        data.put("deepLink", deepLink(command));
        return data;
    }

    /**
     * 앱이 자기 문구를 쓰고 싶을 때의 열쇠.
     *
     * <p>문구 자체는 서버가 갖는다({@link #title}·{@link #body}). 키를 함께 주면 앱이 원할 때
     * 자기 문구로 갈아 끼우고, 안 쓰면 서버 문구가 그대로 보인다 — 다국어를 나중에 열어 두되
     * 지금 설정 키를 늘리지 않는 절충이다.
     */
    private String messageKey(JourneyCheckpoint checkpoint) {
        return switch (checkpoint) {
            case TRANSFER_RISK_AT_RISK -> "journey.transfer.at-risk";
            case TRANSFER_RISK_MISSED -> "journey.transfer.missed";
            case TRANSFER_TARGET_APPROACHING -> "journey.transfer.approaching";
            case REROUTE_SUGGESTED -> "journey.reroute.suggested";
        };
    }

    /**
     * 알림을 눌렀을 때 앱이 열 자리.
     *
     * <p>서버가 <b>완성된 문자열</b>로 준다. 앱이 식별자로 조립하게 하면 라우팅 규칙이 서버와 앱
     * 두 벌이 되어, 경로가 하나 늘 때마다 양쪽을 함께 고쳐야 한다.
     *
     * <p>{@code scope} 는 이름을 그대로 싣는다. 값이 지금 하나뿐이라고 문자열을 박아 넣으면 leg
     * 범위가 생기는 날 조용히 틀린 링크가 된다.
     *
     * <p>사용자 식별자나 token 은 넣지 않는다 — 알림은 잠금화면에 그대로 보인다.
     */
    private String deepLink(NotificationSendCommand command) {
        return DEEP_LINK_SCHEME + "://journey/" + command.journeyId()
                + "?alertId=" + command.notificationId()
                + "&scope=" + command.scopeType().name()
                + "&scopeId=" + command.scopeId();
    }

    /** 확정 문구. 바꾸려면 발송을 켜기 <b>전</b>에 바꾼다 — 켠 뒤의 변경은 사용자 눈에 보인다. */
    private String title(JourneyCheckpoint checkpoint) {
        return switch (checkpoint) {
            case TRANSFER_RISK_AT_RISK -> "환승 시간이 빠듯해요";
            case TRANSFER_RISK_MISSED -> "환승을 놓쳤어요";
            case TRANSFER_TARGET_APPROACHING -> "갈아탈 차가 곧 도착해요";
            case REROUTE_SUGGESTED -> "더 빠른 길이 있어요";
        };
    }

    /**
     * 보드 push(#264 PR-5)는 payload 에 정류장·노선·이득이 있으면 <b>행동 단위</b>로 쓴다. 숫자는 보낸 순간의
     * 값이고, 누르면 앱이 최신 보드를 다시 받는다. 재료가 없으면(옛 제안 알림) 기존 확정 문구다.
     */
    private String body(NotificationSendCommand command) {
        if (command.checkpoint() == JourneyCheckpoint.REROUTE_SUGGESTED && command.payload() != null) {
            String action = rerouteAction(command.payload());
            if (action != null) {
                return action;
            }
        }
        return body(command.checkpoint());
    }

    private String rerouteAction(com.fasterxml.jackson.databind.JsonNode payload) {
        String route = text(payload, "routeName");
        String stop = text(payload, "stopName");
        int gainSec = payload.path("etaGainSec").asInt(0);
        if (route == null || gainSec < 60) {
            return null;
        }
        long minutes = Math.round(gainSec / 60.0);
        String origin = text(payload, "originType");
        boolean alight = "RIDE_STOP".equals(origin) || "PLANNED_ALIGHT".equals(origin);
        if (alight && stop != null) {
            return stop + "에서 내려 " + route + " 타면 " + minutes + "분 빨라요";
        }
        return route + "(으)로 가면 " + minutes + "분 빨라요";
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode payload, String field) {
        com.fasterxml.jackson.databind.JsonNode node = payload.get(field);
        return node == null || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }

    private String body(JourneyCheckpoint checkpoint) {
        return switch (checkpoint) {
            // "지금 이동하면"이 아니다. ④가 대기 단계의 AT_RISK 를 접근 알림에 넘겼으므로 이
            // 문구가 나가는 상황은 아직 이동 중이고, "이동하면"은 멈춰 있다는 전제를 깐다.
            case TRANSFER_RISK_AT_RISK -> "서두르면 다음 차를 탈 수 있어요.";
            case TRANSFER_RISK_MISSED -> "앱에서 새 안내를 확인하세요.";
            case TRANSFER_TARGET_APPROACHING -> "탑승 준비를 하세요.";
            // 몇 분 빨라지는지는 넣지 않는다. 제안은 5분짜리 스냅샷이라 늦게 도착한 알림의
            // 숫자는 이미 틀린 값이고, 바꾸는 것은 사용자가 목록을 열어서 한다.
            case REROUTE_SUGGESTED -> "앱에서 새 경로를 확인하세요.";
        };
    }
}
