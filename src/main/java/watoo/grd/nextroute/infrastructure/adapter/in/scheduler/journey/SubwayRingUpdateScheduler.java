package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.service.SubwayRingUpdateService;

/**
 * 진행 링을 주기적으로 갱신한다.
 *
 * <h2>왜 5초인가</h2>
 *
 * <p>원천이 15초마다 갱신되고 {@code recptnDt} 자체가 1~2분 뒤처져 있다. 더 자주 돌면 같은 값을
 * 다시 계산할 뿐이다. 그래도 tick 주기보다 원천이 느린 것은 문제가 아니다 — 서버가 하는 일은
 * 앵커를 유지하는 것이고 그 사이는 앱이 채운다.
 *
 * <h2>워커가 여럿이어도 된다</h2>
 *
 * <p>링은 latest-wins 라 둘이 같은 여정을 계산해도 늦게 쓴 쪽이 남고 그 값은 같다. 위험한 것은
 * <b>다른 세대</b>가 덮는 경우인데, 그건 키에 개정본과 고정 세대가 들어 있어 애초에 다른 키다.
 */
@Slf4j
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "journey.subway.ring", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SubwayRingUpdateScheduler {

    private final SubwayRingUpdateService updateService;

    @Scheduled(fixedDelayString = "${journey.subway.ring.tick-ms:5000}")
    public void run() {
        try {
            updateService.runOnce();
        } catch (Exception e) {
            // tick 하나가 죽어도 다음 tick 은 살아 있어야 한다.
            log.error("[SubwayRing] tick 실패: {}", e.getMessage(), e);
        }
    }
}
