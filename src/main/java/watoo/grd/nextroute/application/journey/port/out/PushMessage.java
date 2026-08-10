package watoo.grd.nextroute.application.journey.port.out;

import java.time.Duration;
import java.util.Map;

/**
 * 발송할 push 한 벌. SDK 타입이 아니라 우리 어휘로 적는다.
 *
 * <p>token 은 여기 없다. 같은 메시지를 여러 기기에 보내므로 주소는 발송기가 따로 넘긴다.
 *
 * <p><b>유효시간이 두 값인 이유.</b> Android 는 "앞으로 몇 초"를 받고 APNs 는 "언제(UTC epoch 초)"
 * 를 받는다. 같은 숫자를 양쪽에 넣으면 iOS 알림이 1970년으로 읽혀 즉시 만료된다. 두 값은
 * {@code FcmMessageFactory} 가 하나의 만료 시각에서 파생한다.
 *
 * @param data 상태값이 아니라 <b>식별자만</b> 담는다. push 는 상태 저장소가 아니다
 *             (REQUIREMENTS §4.5) — 늦게 도착한 알림이 낡은 숫자를 보여 준다
 * @param collapseKey 같은 열쇠를 가진 대기 중 메시지를 새 것이 덮는다. APNs 제한이 64바이트다
 * @param androidTtl 지금부터 남은 시간
 * @param apnsExpirationEpochSeconds 만료 시각의 UTC epoch 초
 */
public record PushMessage(String title, String body, Map<String, String> data,
                          String collapseKey, Duration androidTtl,
                          long apnsExpirationEpochSeconds, Priority priority) {

    /** 전달 우선순위. 환승 위험은 즉시 행동이 필요하다(TECHNICAL_DECISIONS §2 LOCKED). */
    public enum Priority {
        HIGH,
        NORMAL
    }
}
