package watoo.grd.nextroute.application.journey.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.port.out.NotificationSender;

/**
 * relay 루프가 실제로 도는 프로세스에서 발송 창구가 no-op 이면 부팅을 막는다.
 *
 * <p><b>켠다고 해 놓고 조용히 아무것도 안 보내는 상태가 가장 나쁘다.</b> relay 가 행을 집어
 * {@code SENT} 로 찍으면서 아무 데도 안 보내면, 그 행은 영영 다시 발송되지 않는다.
 *
 * <p>이 규칙은 {@code JourneyNotificationProperties.validate()} 에 전역으로 있었으나, 그 config 빈은
 * 프로필과 무관하게 모든 프로세스에서 뜬다. relay 루프({@code JourneyNotificationRelayLoop})는
 * {@code @Profile("worker")} + {@code relay.enabled=true} 일 때만 도는데, 전역 검증은 relay 를 절대
 * 돌리지 않는 {@code api} 프로세스(발송 자격이 없어 sender=noop)까지 부팅을 막았다. 그래서 검증을
 * <b>루프와 똑같은 조건</b>으로 옮긴다 — 이 빈은 worker + relay.enabled 일 때만 존재한다.
 *
 * <p>{@code sender} 문자열이 아니라 <b>배선된 {@link NotificationSender} 빈</b>에게 직접 묻는다
 * ({@code SubwayRingPushWiringValidator} 와 같은 이유 — 창구 교체·시험 대역이 정상 사용이라 문자열
 * 조건은 따라 흔들린다).
 */
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "journey.notification.relay", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class JourneyRelaySenderGuard {

    private final NotificationSender sender;

    @PostConstruct
    void validate() {
        if (!sender.deliversRealMessages()) {
            throw new IllegalStateException(
                    "relay 를 켜면서 sender 를 noop 으로 두면 안 된다. 진짜 알림 행을 아무 데도 "
                            + "보내지 않은 채 SENT 로 소모해, 나중에 실제 발송을 영영 막는다");
        }
    }
}
