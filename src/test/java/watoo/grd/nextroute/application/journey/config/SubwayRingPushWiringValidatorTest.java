package watoo.grd.nextroute.application.journey.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import watoo.grd.nextroute.application.journey.port.out.LiveActivityPushPort;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>켠다고 해 놓고 조용히 아무것도 안 보내는 상태</b>를 부팅에서 막는다.
 *
 * <p>그 상태가 특히 나쁜 이유는 증상이 정상처럼 보이기 때문이다 — 대기열에 행이 쌓이고 재시도가
 * 돌고 지표에 실패가 찍히지만, "자격증명을 안 켰다"는 사실은 아무 데도 안 적힌다.
 */
@DisplayName("원격 갱신 배선 검증")
class SubwayRingPushWiringValidatorTest {

    @Test
    @DisplayName("push 를 켰는데 발송 창구가 없으면 부팅을 막는다")
    void enabledWithoutAPortFailsFast() {
        SubwayRingPushWiringValidator validator =
                new SubwayRingPushWiringValidator(propertiesWithPush(true), absentPort());

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("journey.notification.sender=fcm");
    }

    @Test
    @DisplayName("push 가 꺼져 있으면 창구가 없어도 정상이다 — 기본 설정이 그렇다")
    void disabledNeedsNoPort() {
        SubwayRingPushWiringValidator validator =
                new SubwayRingPushWiringValidator(propertiesWithPush(false), absentPort());

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("창구가 있으면 통과한다 — provider 를 바꿔도 조건이 따라 바뀌지 않는다")
    void anyPortSatisfiesTheCheck() {
        SubwayRingPushWiringValidator validator = new SubwayRingPushWiringValidator(
                propertiesWithPush(true),
                presentPort((deviceToken, activityToken, event, apsTimestamp, contentState) ->
                        LiveActivityPushPort.PushResult.ok()));

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    private JourneySubwayRingProperties propertiesWithPush(boolean enabled) {
        JourneySubwayRingProperties properties = new JourneySubwayRingProperties();
        properties.getPush().setEnabled(enabled);
        return properties;
    }

    private ObjectProvider<LiveActivityPushPort> absentPort() {
        return new StubProvider(null);
    }

    private ObjectProvider<LiveActivityPushPort> presentPort(LiveActivityPushPort port) {
        return new StubProvider(port);
    }

    /** {@code getIfAvailable} 하나만 쓰므로 나머지는 구현하지 않는다. */
    private record StubProvider(LiveActivityPushPort port)
            implements ObjectProvider<LiveActivityPushPort> {

        @Override
        public LiveActivityPushPort getIfAvailable() {
            return port;
        }

        @Override
        public LiveActivityPushPort getObject(Object... args) {
            return port;
        }

        @Override
        public LiveActivityPushPort getObject() {
            return port;
        }

        @Override
        public LiveActivityPushPort getIfUnique() {
            return port;
        }
    }
}
