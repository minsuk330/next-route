package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.service.SubwayRingPushRelayService;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 잠금화면 갱신을 실제로 내보낸다.
 *
 * <h2>왜 갱신기와 스케줄러를 나누나</h2>
 *
 * <p>링 계산은 Redis 와 실시간 캐시만 만지고 끝나지만 발송은 외부 provider 왕복이다. 한 tick 에
 * 묶으면 FCM 이 느린 동안 링 계산이 함께 밀린다 — 화면이 빨라야 할 때 정확히 느려진다.
 *
 * <h2>워커가 여럿이어도 된다</h2>
 *
 * <p>claim 이 {@code SKIP LOCKED} 라 서로 기다리지 않고, 같은 행을 두 번 집지도 않는다. 남는
 * 위험은 "집어 온 뒤 더 새 화면이 생기는 것"인데 그것은 발송 직전 재확인이 막는다.
 */
@Slf4j
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "journey.subway.ring.push", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SubwayRingPushRelayScheduler {

    /**
     * 그물 주기(tick 수).
     *
     * <p>설정으로 빼지 않는다. 이 값이 정하는 것은 "고착된 행이 몇 초 뒤에 되돌아오나"뿐이고,
     * tick 마다 부르면 아무것도 안 고치는 UPDATE 두 개가 초당 한 번씩 나간다.
     */
    private static final long SWEEP_EVERY_TICKS = 30;

    private final SubwayRingPushRelayService relayService;
    private final AtomicLong ticks = new AtomicLong();

    @Scheduled(fixedDelayString = "${journey.subway.ring.push.relay-interval-ms:2000}")
    public void run() {
        try {
            relayService.runOnce();
            if (ticks.incrementAndGet() % SWEEP_EVERY_TICKS == 0) {
                int swept = relayService.sweep();
                if (swept > 0) {
                    log.info("[SubwayRingPush] 고착·만료 {}건 정리", swept);
                }
            }
        } catch (Exception e) {
            // tick 하나가 죽어도 다음 tick 은 살아 있어야 한다.
            log.error("[SubwayRingPush] relay tick 실패: {}", e.getMessage(), e);
        }
    }
}
