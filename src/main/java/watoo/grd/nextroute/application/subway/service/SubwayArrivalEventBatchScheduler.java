package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/** 지하철 일일 배치의 Worker 전용 시간 트리거. */
@Component
@Profile("worker")
@RequiredArgsConstructor
public class SubwayArrivalEventBatchScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SubwayArrivalEventBatchService batchService;

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void runDailyDerivation() {
        batchService.runForDate(LocalDate.now(KST).minusDays(1));
    }
}
