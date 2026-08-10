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
 *   <li>행 하나는 <b>"이 사용자에게 이 채널로 한 번 알린다"</b>이지 "토큰 하나에 보낸다"가 아니다</li>
 *   <li>{@code SENT} 의 뜻은 <b>한 대라도 provider 에 접수됐다</b>이다</li>
 * </ul>
 *
 * <h2>행 종결 규칙 — 위에서부터 먼저 맞는 줄이 이긴다</h2>
 *
 * <pre>
 * 살아 있는 token 0건   → 영구 실패("no-active-token")
 * 일시 실패 ≥ 1        → 일시 실패   ← 성공이 섞여 있어도 재시도한다
 * 일시 실패 0, 성공 ≥ 1 → 정상 반환(relay 가 SENT)
 * 일시 실패 0, 성공 0   → 영구 실패(FAILED)
 * </pre>
 *
 * <p><b>두 번째 줄이 핵심이다.</b> {@code 성공 1 + UNAVAILABLE 1} 을 {@code SENT} 로 끝내면 그
 * 일시 실패한 기기는 <b>영원히 못 받는다</b>. 재시도로 이미 성공한 기기에 중복이 가는 것은 받아
 * 들인 비용이다 — payload 에 상태를 담지 않고 collapse key 를 쓰므로 해가 작다.
 *
 * <p>전부 영구 실패면 사용자는 아무것도 못 받았다. 그것을 {@code SENT} 로 찍으면 지표가 거짓말을
 * 한다. 죽은 token({@code UNREGISTERED})은 구현이 비활성화하되, 그것으로 행이 성공이 되지는 않는다.
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
