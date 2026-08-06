package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

/**
 * 멱등 키 두 개를 만드는 <b>유일한</b> 자리.
 *
 * <p>두 키의 형식이 여기저기 흩어지면 한쪽만 고쳤을 때 아무 데서도 안 터지고 <b>중복 알림</b>으로만
 * 드러난다.
 *
 * <ul>
 *   <li>논리 키 — {@code journeyId:routeRevision:scopeType:scopeId:checkpoint}.
 *       "사용자에게 두 번 보이지 않게"</li>
 *   <li>발송 키 — 논리 키 + {@code :channel}. "한 채널로 두 번 보내지 않게".
 *       DB unique 와 {@code ON CONFLICT} 의 중재자</li>
 * </ul>
 *
 * <p>경로 개정본을 키에 넣는 것이 중요하다. 재탐색으로 경로가 바뀌면 같은 환승 순번이라도 다른
 * 환승이므로 알림이 새로 나가야 한다.
 */
public final class JourneyNotificationKeys {

    private JourneyNotificationKeys() {
    }

    public static String logical(long journeyId, int routeRevision,
                                 JourneyNotificationScope scope, int scopeId,
                                 JourneyCheckpoint checkpoint) {
        return journeyId + ":" + routeRevision + ":" + scope.name() + ":" + scopeId
                + ":" + checkpoint.name();
    }

    public static String delivery(String logicalKey, NotificationChannel channel) {
        return logicalKey + ":" + channel.name();
    }
}
