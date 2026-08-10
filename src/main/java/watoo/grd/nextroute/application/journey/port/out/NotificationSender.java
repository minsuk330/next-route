package watoo.grd.nextroute.application.journey.port.out;

import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.exception.NotificationSendException;

/**
 * 채널을 모르는 발송 포트. 기본 구현은 아무것도 안 보내고, {@code sender=fcm} 이면 FCM 구현이 뜬다.
 *
 * <h2>계약 — 행 하나 = "사용자 × 채널"</h2>
 *
 * <p>사용자당 기기가 여럿일 수 있다(PR1 이 사용자당 여러 push token 을 허용했다). 그런데 outbox
 * 행은 채널당 하나다. 그래서 뜻을 이렇게 못박는다.
 *
 * <ul>
 *   <li>행 하나는 <b>"이 사용자에게 이 채널로 한 번 알린다"</b>이다</li>
 *   <li>{@code SENT} 의 뜻은 <b>provider 가 접수했다</b>이다</li>
 * </ul>
 *
 * <h2>기기는 한 대다</h2>
 *
 * <p>표({@code user_push_token})는 사용자당 여러 token 을 허용하지만 <b>알림은 가장 최근에
 * 등록한 기기 한 대에만 간다</b>. 같은 알림을 폰과 태블릿에 동시에 띄우는 것은 요구사항이
 * 아니고, 여러 대로 보내면 행 하나의 결말을 기기별 결과에서 접어야 해 규칙이 크게 늘어난다
 * (부분 실패 판정·결과 순서 보존·대량 비활성화 방어).
 *
 * <h2>행 종결 규칙</h2>
 *
 * <pre>
 * 살아 있는 token 0건 → 영구 실패("no-active-token")
 * provider 가 접수    → 정상 반환(relay 가 SENT)
 * 영구 실패 코드      → 영구 실패(FAILED). 재시도하지 않는다
 * 그 밖의 실패        → 일시 실패. 백오프 뒤 재시도
 * </pre>
 *
 * <p><b>모르는 코드는 일시 실패로 본다.</b> 영구로 단정하면 알림이 조용히 사라진다. 반대로
 * 죽은 token({@code UNREGISTERED})은 구현이 비활성화하되, 그 행은 여전히 실패다 — 사용자는
 * 아무것도 못 받았고, {@code SENT} 로 찍으면 지표가 거짓말을 한다.
 *
 * <p>토큰별 발송 상태 표는 만들지 않는다. 필요해지면 그때 판단한다.
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
