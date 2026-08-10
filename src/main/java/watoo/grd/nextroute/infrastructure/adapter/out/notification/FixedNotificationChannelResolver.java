package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.port.out.NotificationChannelResolver;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.util.List;

/**
 * 설정이 정한 채널 목록을 그대로 돌려준다. 이 PR 의 기본값은 {@code [PUSH]} 하나다.
 *
 * <p>사용자를 보지 않는다 — 누구에게 어느 길이 열려 있는지는 로그인 방식과 등록된 token 을
 * 봐야 알 수 있고, 그것을 읽는 구현은 PR3 이 붙인다. 여기서 미리 흉내 내면 PR3 이 이 파일을
 * 고쳐야 한다.
 *
 * <p>목록은 부팅 시 한 번만 만든다. 초당 수백 번 불릴 수 있는 자리라 매번 문자열을 파싱하지
 * 않는다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "journey.notification", name = "channel-resolver",
        havingValue = "fixed", matchIfMissing = true)
public class FixedNotificationChannelResolver implements NotificationChannelResolver {

    private final JourneyNotificationProperties properties;

    private List<NotificationChannel> channels;

    /** 모르는 채널 이름은 여기서 터진다. 발송 시점에 터지면 알림 한 건이 조용히 사라진다. */
    @PostConstruct
    void init() {
        channels = properties.getFixedChannels().stream()
                .map(NotificationChannel::valueOf)
                .toList();
    }

    @Override
    public List<NotificationChannel> resolve(long userId) {
        return channels;
    }
}
