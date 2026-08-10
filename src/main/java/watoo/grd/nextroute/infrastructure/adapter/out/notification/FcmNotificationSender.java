package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.exception.NotificationSendException;
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;
import watoo.grd.nextroute.application.journey.port.out.FirebaseMessagingClient;
import watoo.grd.nextroute.application.journey.port.out.FirebaseMessagingClient.PushResult;
import watoo.grd.nextroute.application.journey.port.out.NotificationSender;
import watoo.grd.nextroute.application.push.service.PushTokenDeactivationService;
import watoo.grd.nextroute.application.push.service.PushTokenDeactivationService.ObservedToken;
import watoo.grd.nextroute.domain.push.entity.UserPushToken;
import watoo.grd.nextroute.domain.push.repository.UserPushTokenRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/**
 * FCM 으로 실제 발송한다. <b>기기 한 대</b>에 보낸다.
 *
 * <p>표는 사용자당 여러 token 을 허용하지만 알림은 가장 최근에 등록한 token 하나로만 간다.
 * 같은 알림을 여러 기기에 동시에 띄우는 것은 요구사항이 아니고, 그렇게 하면 행 하나의 결말을
 * 기기별 결과에서 접어야 해 규칙이 크게 늘어난다. 대상 선택 규칙은
 * {@code UserPushTokenRepository} 가 소유한다.
 *
 * <p>결말은 셋 중 하나다.
 * <ol>
 *   <li>살아 있는 token 이 없다 → 영구 실패({@code no-active-token}). 재시도해도 대개 같다.
 *       {@code CANCELED} 와는 다르다 — 그쪽은 "보낼 필요가 없어졌다"이고 이것은 "보낼 곳이 없다"</li>
 *   <li>provider 가 접수했다 → 정상 반환(relay 가 {@code SENT})</li>
 *   <li>실패했다 → 코드로 영구·일시를 가른다. <b>모르는 코드는 일시</b>다 — 영구로 단정하면
 *       알림이 조용히 사라진다</li>
 * </ol>
 *
 * <p><b>token 을 끄는 것은 {@code UNREGISTERED} 하나뿐이다.</b> {@code INVALID_ARGUMENT} 는
 * payload·TTL·크기 문제로도 나고 {@code SENDER_ID_MISMATCH} 는 서버 자격증명이 틀린 것이다. 둘을
 * 비활성화 사유로 쓰면 설정 실수 한 번이 사용자들의 주소록을 지운다.
 *
 * <p><b>token 문자열은 로그·지표·예외 문구 어디에도 싣지 않는다</b>(REQUIREMENTS §6). 식별은
 * {@code notificationId} 로 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "journey.notification", name = "sender", havingValue = "fcm")
public class FcmNotificationSender implements NotificationSender {

    /** 재시도해도 답이 달라지지 않는 코드. 그 밖은 전부 일시 실패로 본다. */
    private static final Set<String> PERMANENT_CODES = Set.of(
            "UNREGISTERED", "INVALID_ARGUMENT", "SENDER_ID_MISMATCH", "THIRD_PARTY_AUTH_ERROR");

    /** 우리 설정·조립이 틀렸다는 신호. token 이 아니라 사람이 봐야 한다. */
    private static final Set<String> OUR_FAULT_CODES = Set.of(
            "INVALID_ARGUMENT", "SENDER_ID_MISMATCH", "THIRD_PARTY_AUTH_ERROR");

    /** 앱 삭제·재설치로 죽은 token. 끄는 사유는 이것 하나뿐이다. */
    private static final String UNREGISTERED = "UNREGISTERED";

    private final UserPushTokenRepository tokenRepository;
    private final FirebaseMessagingClient messagingClient;
    private final FcmMessageFactory messageFactory;
    private final PushTokenDeactivationService deactivationService;
    private final JourneyNotificationMetrics metrics;
    private final Clock clock;

    @Override
    public void send(NotificationSendCommand command) {
        UserPushToken target = tokenRepository
                .findFirstByUserIdAndEnabledTrueAndDeletedAtIsNullOrderByLastSeenAtDescIdDesc(
                        command.userId())
                .orElse(null);
        if (target == null) {
            metrics.recordNoActiveToken(command.channel());
            throw new NotificationSendException("no-active-token", true);
        }

        PushResult result = messagingClient.send(
                messageFactory.from(command, Instant.now(clock)), target.getToken());
        if (result.success()) {
            return;
        }

        String code = result.errorCode() == null ? "UNKNOWN" : result.errorCode();
        if (UNREGISTERED.equals(code)) {
            // 발송 시점에 관측한 신원을 함께 넘긴다 — 그 사이 재등록된 행을 꺼 버리지 않으려고.
            metrics.recordTokenDeactivated(deactivationService.deactivateIfSafe(new ObservedToken(
                    target.getId(), target.getUserId(), target.getLastSeenAt())));
        } else if (OUR_FAULT_CODES.contains(code)) {
            log.error("[JourneyNotification] id={} 발송이 {} 로 거부됐다. token 이 아니라 "
                    + "설정·조립을 봐야 한다", command.notificationId(), code);
        }

        // 문구에는 코드만 담는다. token 문자열도 SDK 문구도 넣지 않는다.
        throw new NotificationSendException("fcm: " + code, PERMANENT_CODES.contains(code));
    }
}
