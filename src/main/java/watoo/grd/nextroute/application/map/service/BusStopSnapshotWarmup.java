package watoo.grd.nextroute.application.map.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.map.port.in.GetBusStopSnapshotUseCase;

/**
 * 첫 사용자가 1.3만 행 빌드 비용을 물지 않도록 부팅 직후 스냅샷을 한 번 만들어 둔다.
 *
 * <p>{@code worker} 프로필은 제외한다 — worker 는 이 엔드포인트를 서비스하지 않으므로 힙만 먹는다
 * (compose: api={@code prod,api} / worker={@code prod,worker}).
 *
 * <p>워밍업 실패로 부팅을 막지 않는다. 실패해도 첫 요청에서 지연 빌드로 복구된다.
 */
@Slf4j
@Component
@Profile("!worker")
@RequiredArgsConstructor
public class BusStopSnapshotWarmup {

    private final GetBusStopSnapshotUseCase useCase;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            log.info("버스정류장 스냅샷 워밍업 완료 version={}", useCase.snapshot().version());
        } catch (Exception e) {
            log.warn("버스정류장 스냅샷 워밍업 실패 — 첫 요청에서 지연 빌드한다", e);
        }
    }
}
