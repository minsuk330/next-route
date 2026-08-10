package watoo.grd.nextroute.application.journey.port.out;

import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.exception.NotificationSendException;

/**
 * 채널을 모르는 발송 포트. 이 PR 은 아무것도 안 보내는 구현을 끼우고 PR3 이 FCM·토스 구현을 붙인다.
 *
 * <h2>계약 — 행 하나 = "사용자 × 채널"</h2>
 *
 * <p>사용자당 기기가 여럿일 수 있다(PR1 이 사용자당 여러 push token 을 허용했다). 그런데 outbox
 * 행은 채널당 하나다. 그래서 뜻을 이렇게 못박는다.
 *
 * <ul>
 *   <li>행 하나는 <b>"이 사용자에게 이 채널로 한 번 알린다"</b>이지 "토큰 하나에 보낸다"가 아니다</li>
 *   <li>{@code SENT} 의 뜻은 <b>살아 있는 token 전부에 대해 provider 에 넘겼다</b>이다</li>
 *   <li><b>토큰별 영구 실패는 outbox 가 재시도하지 않는다.</b> 구현이 {@code UNREGISTERED} 인
 *       token 을 비활성화하고 그 행은 {@code SENT} 로 끝낸다</li>
 *   <li><b>토큰별 일시 실패는 행 전체가 재시도한다.</b> 이미 성공한 기기에 중복이 갈 수 있다 —
 *       수용한다. payload 에 상태를 담지 않고 collapse key 를 쓰므로 중복의 해가 작다</li>
 * </ul>
 *
 * <p>토큰별 발송 상태 표는 만들지 않는다. 필요해지면 PR3 이 판단한다.
 *
 * <p>발송 보장은 <b>최소 한 번</b>이다. 발송이 트랜잭션 밖이라 provider 에 넘긴 직후 죽으면
 * 회수 후 다시 간다 — 정확히 한 번을 만들려면 provider 응답과 DB 커밋을 원자적으로 묶어야
 * 하는데 불가능하다.
 *
 * <p>구현은 자체 타임아웃을 {@code journey.notification.claim-timeout-minutes} 보다 짧게 둘
 * 의무가 있다. 넘기면 회수 루프가 아직 발송 중인 행을 다시 집어 간다.
 */
public interface NotificationSender {

    /**
     * 한 건을 보낸다.
     *
     * @throws NotificationSendException 발송 실패. {@code permanent} 로 재시도 여부를 알린다
     */
    void send(NotificationSendCommand command);
}
