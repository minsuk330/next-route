package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.route.config.KmaWeatherProperties;
import watoo.grd.nextroute.application.route.port.out.WeatherPort;
import watoo.grd.nextroute.application.route.port.out.WeatherPort.WeatherFeatures;

/**
 * 현재 날씨를 주기 폴링해 캐시한다. 예측 요청 경로는 {@link #current()}로 캐시만 읽어
 * (차량 수만큼/요청마다) 외부 호출이 발생하지 않게 한다 — deadline 영향 0.
 * <p>
 * 콜드 스타트(첫 폴링 전)나 조회 실패 시 null → 날씨 feature 전부 null(LightGBM NaN).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherSnapshotService {

    private final WeatherPort weatherPort;
    private final KmaWeatherProperties props;

    private volatile WeatherFeatures cached;

    /** 캐시된 최신 관측. 없으면 null. */
    public WeatherFeatures current() {
        return cached;
    }

    /** 경로 검색을 수행하는 각 역할의 {@link WeatherSnapshotRefreshScheduler}가 호출한다. */
    public void refresh() {
        if (!props.isEnabled()) return;
        weatherPort.fetchCurrent().ifPresentOrElse(
                features -> {
                    cached = features;
                    log.debug("[Weather] 캐시 갱신: {}", features);
                },
                () -> log.warn("[Weather] 갱신 실패 — 이전 캐시 유지(null 가능)"));
    }
}
