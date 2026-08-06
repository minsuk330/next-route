package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.port.out.NotificationSender;

/**
 * 아무 데도 보내지 않는 발송기. 이 PR 의 기본값이다.
 *
 * <p>보내지 않아도 "위험 감지 → 큐에 쌓임 → 꺼내짐 → 보냄 처리" 전 구간이 돌아 통합 테스트로
 * 증명할 수 있다. 실제 발송(FCM·토스)은 PR3 이 붙인다.
 *
 * <p>이것을 켠 채 relay 를 켜면 부팅이 막힌다({@code JourneyNotificationProperties#validate}) —
 * 진짜 알림 행을 아무 데도 보내지 않은 채 SENT 로 소모하기 때문이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "journey.notification", name = "sender",
        havingValue = "noop", matchIfMissing = true)
public class NoOpNotificationSender implements NotificationSender {

    @Override
    public void send(NotificationSendCommand command) {
        log.debug("[JourneyNotification] no-op 발송 id={} channel={} checkpoint={}",
                command.notificationId(), command.channel(), command.checkpoint());
    }
}
