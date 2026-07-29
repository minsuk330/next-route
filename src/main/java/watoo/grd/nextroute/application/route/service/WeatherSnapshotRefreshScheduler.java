package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 경로 검색을 수행하는 각 실행 역할의 JVM 로컬 날씨 캐시를 갱신한다. */
@Component
@Profile({"api", "worker"})
@RequiredArgsConstructor
public class WeatherSnapshotRefreshScheduler {

    private final WeatherSnapshotService weatherSnapshotService;

    @Scheduled(initialDelayString = "0", fixedDelayString = "${kma.weather.poll-ms:600000}")
    public void refresh() {
        weatherSnapshotService.refresh();
    }
}
