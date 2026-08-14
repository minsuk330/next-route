package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.service.SubwayBindingMatchService;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 탑승 열차 판정을 주기적으로 돌린다.
 *
 * <h2>왜 평가기 스케줄러에 얹지 않는가</h2>
 *
 * <p>{@link JourneyDueEvaluatorScheduler} 는 여정당 10~180초 주기다. 탑승 판정에는 너무 느리다 —
 * 사용자가 탄 직후 몇 분이 승부인데 그 사이 한두 번밖에 못 본다. 반대로 Redis Stream·리더 선출은
 * 이 규모에 과하다.
 *
 * <h2>워커가 여럿이어도 된다</h2>
 *
 * <p>배포 설정이 워커 1개를 보장하지 않는다. 지금 안전한 이유는 CAS 와 행 잠금이 정확성을 지키기
 * 때문이지 워커가 하나여서가 아니다 — 둘이면 같은 행을 두 번 <b>계산</b>할 뿐이고 반영은 하나만
 * 된다({@code SKIP LOCKED} 로 서로 기다리지도 않는다). 낭비되는 것은 중복 계산량뿐이다.
 */
@Slf4j
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "journey.subway.binding", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SubwayBindingMatchScheduler {

    /**
     * 판정 불가 행 청소 주기(tick 수).
     *
     * <p>설정으로 빼지 않는다. 이 값이 정하는 것은 "접히기까지 몇 초 더 걸리나"뿐이고, 그 행들은
     * 이미 판정 창을 넘겨 아무도 기다리지 않는다. tick 마다 부르면 아무것도 안 지우는 UPDATE 가
     * 초당 한 번씩 나간다.
     */
    private static final long SWEEP_EVERY_TICKS = 60;

    private final SubwayBindingMatchService matchService;
    private final AtomicLong ticks = new AtomicLong();

    @Scheduled(fixedDelayString = "${journey.subway.binding.tick-ms:2000}")
    public void run() {
        try {
            matchService.runOnce();
            if (ticks.incrementAndGet() % SWEEP_EVERY_TICKS == 0) {
                int swept = matchService.sweepStale();
                if (swept > 0) {
                    log.info("[SubwayBindingMatch] 판정 불가 고정 {}건을 접었다", swept);
                }
            }
        } catch (Exception e) {
            // tick 하나가 죽어도 다음 tick 은 살아 있어야 한다.
            log.error("[SubwayBindingMatch] tick 실패: {}", e.getMessage(), e);
        }
    }
}
