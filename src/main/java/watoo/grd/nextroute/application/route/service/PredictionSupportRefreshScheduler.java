package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 경로 검색을 수행하는 각 실행 역할의 JVM 로컬 ML 지원 노선 캐시를 갱신한다. */
@Component
@Profile({"api", "worker"})
@RequiredArgsConstructor
public class PredictionSupportRefreshScheduler {

    private final PredictionSupportService predictionSupportService;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        predictionSupportService.refresh();
    }

    @Scheduled(
            fixedDelayString = "${ml.predictor.supported-routes-refresh-ms:3600000}",
            initialDelayString = "${ml.predictor.supported-routes-refresh-ms:3600000}")
    public void refresh() {
        predictionSupportService.refresh();
    }
}
