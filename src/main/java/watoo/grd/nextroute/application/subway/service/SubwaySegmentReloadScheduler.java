package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Worker가 사용하는 지하철 구간 캐시를 매일 다시 적재한다. */
@Component
@Profile("worker")
@RequiredArgsConstructor
public class SubwaySegmentReloadScheduler {

    private final SubwaySegmentLookup subwaySegmentLookup;

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void reload() {
        subwaySegmentLookup.reload();
    }
}
