package watoo.grd.nextroute.application.journey.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.port.out.NotificationSender;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * relay 가 실제로 도는 프로세스(worker + relay.enabled)에서 발송 창구가 no-op 이면 부팅을 막는지
 * 고정한다.
 *
 * <p>빈 생성 조건({@code @Profile("worker")} + {@code relay.enabled=true})은 스프링이 강제하므로,
 * 여기서는 빈이 실제로 떴다는 전제 아래 {@code validate()} 의 판정만 검증한다 — 배선된 sender 가
 * 진짜 발송 창구가 아니면 던지고, 맞으면 통과.
 */
@DisplayName("relay sender 가드")
class JourneyRelaySenderGuardTest {

    /** 실제 발송 창구(default deliversRealMessages()=true). */
    private static final NotificationSender REAL_SENDER = command -> { };

    /** no-op 창구(deliversRealMessages()=false) — NoOpNotificationSender 와 같은 판정. */
    private static final NotificationSender NOOP_SENDER = new NotificationSender() {
        @Override
        public void send(NotificationSendCommand command) { }

        @Override
        public boolean deliversRealMessages() {
            return false;
        }
    };

    private void validate(NotificationSender sender) {
        ReflectionTestUtils.invokeMethod(new JourneyRelaySenderGuard(sender), "validate");
    }

    @Test
    @DisplayName("no-op sender 면 막는다 — 진짜 알림 행을 안 보낸 채 SENT 로 소모한다")
    void noopSenderIsRejected() {
        assertThatThrownBy(() -> validate(NOOP_SENDER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("noop");
    }

    @Test
    @DisplayName("실제 발송 창구면 통과한다")
    void realSenderPasses() {
        assertThatCode(() -> validate(REAL_SENDER)).doesNotThrowAnyException();
    }
}
