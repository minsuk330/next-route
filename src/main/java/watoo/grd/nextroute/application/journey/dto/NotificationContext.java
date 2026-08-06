package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.util.List;

/**
 * 평가 트랜잭션 <b>밖</b>에서 미리 정해 두고 쓰기 경로에 넘기는 발송 정보.
 *
 * <p>{@code JourneyEvaluationOutcome} 에 넣지 않는다 — 그쪽은 "무엇을 계산했나"이고 이쪽은
 * "어디로 보내나"다. 섞으면 계산 DTO 가 알림 관심사를 알게 되고, PR3 이 채널 결정 구현을
 * 갈아 끼울 때 계산 쪽을 건드리게 된다.
 *
 * <p>채널을 트랜잭션 밖에서 정하는 이유도 같다. PR3 의 구현은 DB 를 읽는데, 그 읽기가 평가
 * 트랜잭션 안에 들어오면 트랜잭션이 그만큼 길어진다.
 *
 * @param channels 이 사용자에게 보낼 길. <b>비어 있을 수 있다</b> — 그때는 아무것도 쌓지 않고
 *                 마커도 쓰지 않는다(마커를 앞당기면 그 환승은 영영 알림이 없다)
 */
public record NotificationContext(long userId, int currentLegIndex,
                                  List<NotificationChannel> channels) {
}
