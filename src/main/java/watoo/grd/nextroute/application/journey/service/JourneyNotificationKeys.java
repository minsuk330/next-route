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

    /**
     * 보드 push 의 논리 키. 보드는 개정본 동안 제안 행이 하나라 push 순번을 붙인다 — 제안 번호만으로는 두 번째
     * push 가 {@code ON CONFLICT} 에 삼켜진다.
     */
    public static String boardPush(long journeyId, int routeRevision, long proposalId, int pushSeq) {
        return logical(journeyId, routeRevision, JourneyNotificationScope.JOURNEY, (int) proposalId,
                JourneyCheckpoint.REROUTE_SUGGESTED) + ":p" + pushSeq;
    }

    /**
     * 논리 키가 가리키는 체크포인트. 형식이 다르거나 모르는 값이면 null.
     *
     * <p>울릴 권리 칸에는 논리 키만 남는다. "이미 울린 것이 재탐색 제안인가"를 알려고 알림 행을 다시 읽지 않게
     * 키에서 바로 읽는다 — 키 형식을 여기서만 정하므로 여기서 읽는 것도 안전하다.
     */
    public static JourneyCheckpoint checkpointOf(String logicalKey) {
        if (logicalKey == null) {
            return null;
        }
        String[] parts = logicalKey.split(":");
        if (parts.length < 5) {
            return null;
        }
        try {
            return JourneyCheckpoint.valueOf(parts[4]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
