package watoo.grd.nextroute.application.journey.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.service.JourneyOutboxEnqueuedEvent;
import watoo.grd.nextroute.application.journey.service.JourneyRelayWakeListener;
import watoo.grd.nextroute.application.journey.service.JourneyRelayWaker;
import watoo.grd.nextroute.application.notification.service.RelayWaker;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 여정 enqueue 는 <b>여정 relay 만</b> 깨운다.
 *
 * <p>두 waker 를 하나로 합치고 싶은 유혹이 늘 있지만, 합치면 여정 enqueue 하나가 버스 relay 까지
 * 깨운다. 여정은 초당 수백 건이라 그 낭비가 크다.
 *
 * <p>신호가 하나로 합쳐지는 것(coalescing)도 함께 고정한다. 몰아치는 enqueue 가 drain 을 그 수만큼
 * 돌리면 빈 배치를 계속 긁는다.
 */
@DisplayName("여정 relay 깨우기")
class JourneyRelayWakeupTest {

    @Test
    @DisplayName("여정 알림이 쌓이면 여정 relay 만 깨어난다")
    void journeyEventDoesNotWakeBusRelay() {
        JourneyRelayWaker journeyWaker = new JourneyRelayWaker();
        RelayWaker busWaker = new RelayWaker();
        JourneyRelayWakeListener listener = new JourneyRelayWakeListener(journeyWaker);

        listener.onEnqueued(new JourneyOutboxEnqueuedEvent());

        assertThat(journeyWaker.await(10)).isTrue();
        assertThat(busWaker.await(10))
                .as("버스 relay 가 여정 enqueue 마다 깨면 빈 배치를 계속 긁는다")
                .isFalse();
    }

    @Test
    @DisplayName("여러 번 깨워도 신호는 하나로 합쳐진다")
    void signalsAreCoalesced() {
        JourneyRelayWaker waker = new JourneyRelayWaker();

        waker.wake();
        waker.wake();
        waker.wake();

        assertThat(waker.await(10)).isTrue();
        assertThat(waker.await(10))
                .as("합치지 않으면 몰아친 enqueue 수만큼 drain 이 돈다")
                .isFalse();
    }

    @Test
    @DisplayName("신호가 없으면 시간 초과로 깨어난다 — 그것이 복구 경로다")
    void timeoutIsTheFallbackSweep() {
        JourneyRelayWaker waker = new JourneyRelayWaker();

        assertThat(waker.await(10))
                .as("신호는 힌트일 뿐이다. 유실되면 sweep 주기가 복구한다")
                .isFalse();
    }
}
