package watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.service.TrackBusVehicleWatchService;

/**
 * 단독 추적 버스 관찰·차량 대조(#265 PR-4). <b>worker 프로필 전용</b> — 공용 빈으로 두면 api 도 같은 노선을 조회해 예산을
 * 두 배로 쓰고, M7 선례처럼 api 부팅 쪽 배선 문제로 번진다.
 */
@Slf4j
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "journey.track.bus", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrackBusVehicleWatchScheduler {

    private final TrackBusVehicleWatchService watchService;

    @Scheduled(fixedDelayString = "${journey.track.bus.tick-ms:15000}")
    public void run() {
        try {
            watchService.tick();
        } catch (Exception e) {
            // tick 하나가 죽어도 다음 tick 은 살아 있어야 한다.
            log.error("[TrackBus] tick 실패: {}", e.getMessage(), e);
        }
    }
}
