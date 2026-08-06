package watoo.grd.nextroute.application.journey.port.out;

import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.util.List;

/**
 * 이 사용자에게 어느 길로 보낼지 정한다.
 *
 * <p>이 PR 의 기본 구현은 설정이 정한 목록 하나를 그대로 준다. PR3 이 사용자의 로그인 방식과
 * 등록된 push token 을 보는 구현으로 갈아 끼운다({@code @ConditionalOnProperty}).
 *
 * <h2>계약</h2>
 * <ul>
 *   <li><b>절대 예외를 던지지 않는다.</b> 알림 실패가 여정 평가를 실패시키면 안 된다</li>
 *   <li>{@code null} 을 반환하지 않는다. 보낼 곳이 없으면 빈 목록이다</li>
 * </ul>
 *
 * <p>부르는 쪽도 빈 목록을 방어한다 — 비었는데 "알렸다" 마커를 쓰면 그 환승은 영영 알림이 없다.
 */
public interface NotificationChannelResolver {

    List<NotificationChannel> resolve(long userId);
}
