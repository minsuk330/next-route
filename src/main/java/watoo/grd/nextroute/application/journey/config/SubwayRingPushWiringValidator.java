package watoo.grd.nextroute.application.journey.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.port.out.LiveActivityPushPort;

/**
 * 원격 갱신을 켰는데 보낼 창구가 없는 조합을 부팅에서 막는다.
 *
 * <p><b>켠다고 해 놓고 조용히 아무것도 안 보내는 상태가 가장 나쁘다.</b> 그 상태에서는 대기열에
 * 행이 쌓이고, 재시도가 돌고, 지표에는 실패가 찍히지만, 원인이 "자격증명을 안 켰다"라는 것은
 * 아무 데도 안 적힌다.
 *
 * <p>{@code journey.notification.sender} 를 직접 보지 않고 <b>포트 빈의 존재</b>로 판정한다.
 * 창구를 바꾸거나(다른 provider) 시험에서 대역을 끼우는 것이 정상 사용이고, 그때마다 이 조건이
 * 따라 바뀌면 안 되기 때문이다.
 *
 * <p>같은 이유로 {@code JourneyNotificationProperties} 가 "relay 를 켠 채 sender=noop" 조합을
 * 막는다. 이 파일은 그 규칙의 지하철 링 판이다.
 */
@Component
@RequiredArgsConstructor
public class SubwayRingPushWiringValidator {

    private final JourneySubwayRingProperties properties;
    private final ObjectProvider<LiveActivityPushPort> pushPort;

    @PostConstruct
    void validate() {
        if (!properties.getPush().isEnabled()) {
            return;
        }
        if (pushPort.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "journey.subway.ring.push.enabled 를 켰는데 Live Activity 발송 창구가 없다. "
                            + "FCM 으로 보내려면 journey.notification.sender=fcm 과 자격증명 마운트가 "
                            + "함께 있어야 한다");
        }
    }
}
