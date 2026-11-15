package watoo.grd.nextroute.infrastructure.adapter.in.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.regionalsubway.config.RegionalSubwayTimetableProperties;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalSourceDriftCheck;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalSubwayTimetableLoadService;

/**
 * 지방 지하철 공식 시간표 적재. 주 1회 + 출발 표가 비어 있을 때 부팅 직후 1회.
 *
 * <p>원격 호출은 대전(TAGO 22역 × 4)과 부산(114역 × 1) 약 200회. 대구·광주는 리소스 파일이다.
 * 부팅을 막지 않게 별도 스레드에서 돈다.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "regional-subway.timetable", name = "enabled", havingValue = "true")
public class RegionalSubwayTimetableScheduler {

    private final RegionalSubwayTimetableLoadService loadService;
    private final RegionalSubwayTimetableProperties properties;
    private final RegionalSourceDriftCheck driftCheck;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!properties.isLoadOnEmpty()) {
            return;
        }
        boolean empty;
        try {
            empty = loadService.isEmpty();
        } catch (RuntimeException e) {
            log.warn("[RegionalSubway] table check failed (V76 missing?): {}", e.getMessage());
            return;
        }
        if (empty) {
            log.info("[RegionalSubway] departure table empty; initial load starting");
            Thread thread = new Thread(loadService::load, "regional-subway-initial-load");
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Scheduled(cron = "${regional-subway.timetable.cron}", zone = "Asia/Seoul")
    public void weeklyLoad() {
        log.info("[RegionalSubway] weekly load starting");
        try {
            loadService.load();
        } catch (RuntimeException e) {
            log.warn("[RegionalSubway] weekly load failed: {}", e.toString());
        }
        try {
            driftCheck.run();   // 파일 원천(대구, 광주) 낡음 경고. 값은 안 바꾼다
        } catch (RuntimeException e) {
            log.warn("[RegionalSubway] drift check failed: {}", e.toString());
        }
    }
}
