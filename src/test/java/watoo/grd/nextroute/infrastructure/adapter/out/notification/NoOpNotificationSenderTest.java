package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpNotificationSenderTest {

    @Test
    @DisplayName("no-op 은 실제 발송 창구가 아니라고 보고한다 — relay 가드가 이 값으로 부팅을 막는다")
    void doesNotDeliverRealMessages() {
        assertThat(new NoOpNotificationSender().deliversRealMessages()).isFalse();
    }
}
