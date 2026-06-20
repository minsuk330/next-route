package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.route.port.out.MlSupportedRoutesPort;

import java.util.Set;

/**
 * "환승 예측 가능" 배지 판정의 단일 소스.
 * ML 모델의 route_categories(학습된 route_id)를 캐싱하고 routeId 단위로 지원 여부를 답한다.
 *
 * <p>판정 소스는 수집 대상(target-route-names)이 아니라 <b>모델이 실제 학습한 노선</b>이다.
 * serving 비활성/장애 시 직전 캐시를 유지하며, 초기 캐시가 없으면 빈 집합 → 모든 배지 false(안전).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionSupportService {

    private final MlSupportedRoutesPort supportedRoutesPort;

    private volatile Set<String> supportedRouteIds = Set.of();

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        refresh();
    }

    /** 모델 지원 노선 목록 주기 갱신 (1시간). serving 불가 시 직전 캐시 유지. */
    @Scheduled(fixedDelayString = "${ml.predictor.supported-routes-refresh-ms:3600000}",
            initialDelayString = "${ml.predictor.supported-routes-refresh-ms:3600000}")
    public void refresh() {
        supportedRoutesPort.fetchSupportedRouteIds().ifPresentOrElse(
                routeIds -> {
                    supportedRouteIds = Set.copyOf(routeIds);
                    log.info("[PredictionSupport] refreshed: {} supported routes", supportedRouteIds.size());
                },
                () -> log.debug("[PredictionSupport] refresh skipped/failed; keeping {} cached routes",
                        supportedRouteIds.size()));
    }

    public boolean isSupported(String routeId) {
        return routeId != null && supportedRouteIds.contains(routeId);
    }

    public Set<String> supportedRouteIds() {
        return supportedRouteIds;
    }
}
