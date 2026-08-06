package watoo.grd.nextroute.application.journey.dto;

import com.fasterxml.jackson.databind.JsonNode;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.time.Instant;

/**
 * relay 가 sender 에게 넘기는 발송 한 건.
 *
 * <p>엔티티를 그대로 넘기지 않는다 — sender 는 트랜잭션 밖에서 돌고, 관리 중인 엔티티를
 * 그쪽에 주면 트랜잭션이 끝난 뒤의 변경이 어디에도 반영되지 않는다.
 *
 * <p>{@code expiresAt} 을 싣는 이유: PR3 이 이 값으로 FCM TTL 을 만든다. 늦게 도착한 환승
 * 알림은 사용자를 엉뚱한 때에 움직인다.
 *
 * @param notificationId outbox 행 번호. 로그와 지표에서 한 건을 따라가는 열쇠
 */
public record NotificationSendCommand(long notificationId, long userId,
                                      NotificationChannel channel, JourneyCheckpoint checkpoint,
                                      JsonNode payload, Instant expiresAt) {
}
