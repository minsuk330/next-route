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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * FCM 으로 실제 발송한다. 사용자의 살아 있는 token <b>전부</b>에 한 번에 보낸다.
 *
 * <p>어려운 부분은 "보내는 것"이 아니라 <b>부분 실패를 어떻게 접느냐</b>다. 기기 셋 중 하나가
 * 실패했을 때 행 하나를 성공으로 볼지 재시도할지는 계약이 정한다({@link NotificationSender}).
 *
 * <ol>
 *   <li>살아 있는 token 0건 → 영구 실패({@code no-active-token}). 재시도해도 대개 같다</li>
 *   <li>일시 실패가 하나라도 있으면 <b>성공이 섞여 있어도</b> 일시 실패다 — 아니면 그 기기는
 *       영원히 못 받는다</li>
 *   <li>일시 실패가 없고 성공이 있으면 정상 반환</li>
 *   <li>전부 영구 실패면 영구 실패. 사용자는 아무것도 못 받았다</li>
 * </ol>
 *
 * <p><b>token 을 끄는 것은 {@code UNREGISTERED} 하나뿐이다.</b> {@code INVALID_ARGUMENT} 는
 * payload·TTL·크기 문제로도 나고 {@code SENDER_ID_MISMATCH} 는 서버 자격증명이 틀린 것이다. 둘을
 * 비활성화 사유로 쓰면 설정 실수 한 번이 주소록을 통째로 지운다.
 *
 * <p><b>token 문자열은 로그·지표·예외 문구 어디에도 싣지 않는다</b>(REQUIREMENTS §6). 실패는
 * 개수만 센다. SDK 예외 문구도 그대로 옮기지 않는다 — token 이 들어 있을 수 있다.
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
        List<UserPushToken> tokens =
                tokenRepository.findByUserIdAndEnabledTrueAndDeletedAtIsNull(command.userId());
        if (tokens.isEmpty()) {
            // CANCELED 가 아니다. 그것은 "보낼 필요가 없어졌다"는 뜻이고, 이것은 "보낼 곳이
            // 없다"는 사실이다.
            metrics.recordNoActiveToken(command.channel());
            throw new NotificationSendException("no-active-token", true);
        }

        List<PushResult> results = messagingClient.send(
                messageFactory.from(command, Instant.now(clock)),
                tokens.stream().map(UserPushToken::getToken).toList());

        Outcome outcome = classify(command, tokens, results);

        // 끄는 것은 UNREGISTERED 뿐이고, 상한을 넘으면 하나도 끄지 않는다.
        metrics.recordTokenDeactivated(deactivationService.deactivateIfSafe(outcome.unregistered()));
        metrics.recordPushTokens("success", outcome.successCount());
        metrics.recordPushTokens("transient", outcome.transientCount());
        metrics.recordPushTokens("permanent", outcome.permanentCount());

        if (outcome.transientCount() > 0) {
            // 성공이 있어도 재시도한다. 아니면 일시 실패한 기기는 영원히 못 받는다.
            throw new NotificationSendException(outcome.summary(), false);
        }
        if (outcome.successCount() > 0) {
            return;
        }
        throw new NotificationSendException(outcome.summary(), true);
    }

    /**
     * token 과 결과를 <b>한 자리에서만</b> 짝짓는다. 여러 곳에서 zip 하면 순서가 어긋날 여지가
     * 생기고, 어긋나면 엉뚱한 token 이 꺼진다.
     */
    private Outcome classify(NotificationSendCommand command, List<UserPushToken> tokens,
                             List<PushResult> results) {
        if (results.size() != tokens.size()) {
            // 경계 구현이 순서·길이를 보장한다. 깨졌다면 짝을 믿을 수 없으니 아무것도 끄지 않고
            // 재시도한다.
            throw new NotificationSendException(
                    "fcm-result-mismatch: tokens=" + tokens.size() + " results=" + results.size(),
                    false);
        }

        int success = 0;
        int transientFailures = 0;
        int permanent = 0;
        List<ObservedToken> unregistered = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i++) {
            PushResult result = results.get(i);
            if (result.success()) {
                success++;
                continue;
            }
            String code = result.errorCode() == null ? "UNKNOWN" : result.errorCode();
            if (!PERMANENT_CODES.contains(code)) {
                // 모르는 코드를 영구로 단정하지 않는다 — 단정하면 알림이 조용히 사라진다.
                transientFailures++;
                continue;
            }
            permanent++;
            if (UNREGISTERED.equals(code)) {
                UserPushToken token = tokens.get(i);
                unregistered.add(new ObservedToken(
                        token.getId(), token.getUserId(), token.getLastSeenAt()));
            } else if (OUR_FAULT_CODES.contains(code)) {
                log.error("[JourneyNotification] id={} 발송이 {} 로 거부됐다. token 이 아니라 "
                        + "설정·조립을 봐야 한다", command.notificationId(), code);
            }
        }
        return new Outcome(success, transientFailures, permanent, unregistered);
    }

    /**
     * 한 행의 발송 결과 요약.
     *
     * @param unregistered 끌 대상. 발송 시점에 관측한 신원을 함께 들고 간다 — 그 사이에 재등록된
     *                     행을 꺼 버리지 않기 위해서다
     */
    private record Outcome(int successCount, int transientCount, int permanentCount,
                           List<ObservedToken> unregistered) {

        /** <b>개수만</b> 담는다. token 문자열도 SDK 문구도 넣지 않는다. */
        String summary() {
            return "fcm: success=" + successCount + " transient=" + transientCount
                    + " permanent=" + permanentCount + " unregistered=" + unregistered.size();
        }
    }
}
