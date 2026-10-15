package watoo.grd.nextroute.application.journey.port.out;

import java.time.Duration;
import java.util.Map;

/**
 * 발송할 push 한 벌. SDK 타입이 아니라 우리 어휘로 적는다.
 *
 * <p>token 은 여기 없다. 내용과 주소는 다른 관심사라 조립기는 내용만 만들고, 어느 기기로 보낼지는
 * 발송기가 고른다.
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
 * @param delivery 울림·조용한 갱신(#428). null 이면 피로 정책 이전과 똑같이 보낸다
 * @param androidTag 화면에 떠 있는 같은 tag 알림을 덮는다. null 이면 넣지 않는다
 * @param androidChannelId Android 알림 채널. null 이면 넣지 않는다(앱 기본)
 */
public record PushMessage(String title, String body, Map<String, String> data,
                          String collapseKey, Duration androidTtl,
                          long apnsExpirationEpochSeconds, Priority priority,
                          Delivery delivery, String androidTag, String androidChannelId) {

    /** 피로 정책을 쓰지 않는 메시지(정책 꺼짐·다른 종류). */
    public PushMessage(String title, String body, Map<String, String> data,
                       String collapseKey, Duration androidTtl,
                       long apnsExpirationEpochSeconds, Priority priority) {
        this(title, body, data, collapseKey, androidTtl, apnsExpirationEpochSeconds, priority,
                null, null, null);
    }

    public boolean quiet() {
        return delivery == Delivery.QUIET;
    }

    /**
     * 이 알림이 기기를 울리는가(#428).
     *
     * <p>경로 버전마다 울림은 한 번이고, 그 뒤의 악화는 이미 떠 있는 알림을 소리 없이 고쳐 쓴다.
     */
    public enum Delivery {
        ALERTING,
        QUIET
    }

    /** 전달 우선순위. 환승 위험은 즉시 행동이 필요하다(TECHNICAL_DECISIONS §2 LOCKED). */
    public enum Priority {
        HIGH,
        NORMAL
    }
}
