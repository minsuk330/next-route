package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 멱등 키 두 개의 형식을 문자열 그대로 고정한다.
 *
 * <p>형식이 바뀌면 이미 쌓인 행과 새 행이 다른 키를 갖게 돼 <b>같은 알림이 두 번</b> 나간다.
 * 그런데 그것은 컴파일에서도 테스트에서도 안 드러나므로 값을 못박는다.
 */
@DisplayName("알림 키")
class JourneyNotificationKeysTest {

    @Test
    @DisplayName("논리 키는 여정·개정본·대상·체크포인트를 이 순서로 잇는다")
    void logicalKeyFormat() {
        String key = JourneyNotificationKeys.logical(42L, 3,
                JourneyNotificationScope.TRANSFER, 1, JourneyCheckpoint.TRANSFER_RISK_AT_RISK);

        assertThat(key).isEqualTo("42:3:TRANSFER:1:TRANSFER_RISK_AT_RISK");
    }

    @Test
    @DisplayName("발송 키는 논리 키 뒤에 채널만 붙인다")
    void deliveryKeyExtendsLogicalKey() {
        String logical = JourneyNotificationKeys.logical(42L, 3,
                JourneyNotificationScope.TRANSFER, 1, JourneyCheckpoint.TRANSFER_RISK_MISSED);

        String delivery = JourneyNotificationKeys.delivery(logical, NotificationChannel.PUSH);

        assertThat(delivery).isEqualTo(logical + ":PUSH");
        assertThat(delivery).startsWith(logical + ":");
    }

    @Test
    @DisplayName("상태가 다르면 발송 키도 다르다")
    void statusIsPartOfTheKey() {
        String atRisk = JourneyNotificationKeys.delivery(JourneyNotificationKeys.logical(
                1L, 1, JourneyNotificationScope.TRANSFER, 0,
                JourneyCheckpoint.TRANSFER_RISK_AT_RISK), NotificationChannel.PUSH);
        String missed = JourneyNotificationKeys.delivery(JourneyNotificationKeys.logical(
                1L, 1, JourneyNotificationScope.TRANSFER, 0,
                JourneyCheckpoint.TRANSFER_RISK_MISSED), NotificationChannel.PUSH);

        assertThat(atRisk).isNotEqualTo(missed);
    }

    @Test
    @DisplayName("현실적인 최대 입력에서도 255자를 넘지 않는다")
    void fitsInColumnLength() {
        String key = JourneyNotificationKeys.delivery(JourneyNotificationKeys.logical(
                Long.MAX_VALUE, Integer.MAX_VALUE, JourneyNotificationScope.TRANSFER,
                Integer.MAX_VALUE, JourneyCheckpoint.TRANSFER_RISK_AT_RISK),
                NotificationChannel.PUSH);

        assertThat(key.length()).isLessThanOrEqualTo(255);
    }
}
