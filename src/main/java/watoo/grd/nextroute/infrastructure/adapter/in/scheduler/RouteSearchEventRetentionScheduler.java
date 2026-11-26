package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.route.log.repository.RouteSearchEventRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 경로 검색 기록(V79) 보관 기간이 지난 행을 매일 지운다. 기본 90일.
 *
 * <p>worker 두 대가 같이 돌아도 같은 행을 지우는 것이라 결과가 같다(리더 락 불필요). 묶음으로 나눠
 * 긴 잠금을 피하고, 한 번에 {@code MAX_BATCHES} 묶음까지만 — 밀린 것은 다음 날 이어서.
 */
@Slf4j
@Component
@Profile("worker")
@ConditionalOnProperty(name = "route.search-event.enabled", havingValue = "true", matchIfMissing = true)
public class RouteSearchEventRetentionScheduler {

    static final int BATCH = 5_000;
    static final int MAX_BATCHES = 40;

    private final RouteSearchEventRepository repository;
    private final Clock clock;
    private final Duration retention;

    public RouteSearchEventRetentionScheduler(RouteSearchEventRepository repository, Clock clock,
                                              @Value("${route.search-event.retention-days:90}") int retentionDays) {
        this.repository = repository;
        this.clock = clock;
        this.retention = Duration.ofDays(retentionDays);
    }

    @Scheduled(cron = "${route.search-event.retention-cron:0 40 4 * * *}", zone = "Asia/Seoul")
    public void purge() {
        Instant cutoff = clock.instant().minus(retention);
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int deleted;
            try {
                deleted = repository.deleteOlderThan(cutoff, BATCH);
            } catch (RuntimeException e) {
                log.warn("[SearchEvent] 보관 기간 정리 실패(내일 다시): {}", e.toString());
                return;
            }
            total += deleted;
            if (deleted < BATCH) {
                break;
            }
        }
        if (total > 0) {
            log.info("[SearchEvent] {}일 지난 검색 기록 {}건 삭제", retention.toDays(), total);
        }
    }
}
