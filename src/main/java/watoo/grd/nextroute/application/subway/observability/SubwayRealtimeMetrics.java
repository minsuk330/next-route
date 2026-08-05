package watoo.grd.nextroute.application.subway.observability;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.concurrent.SingleFlight;

/**
 * 역별 지하철 실시간 조회 지표.
 *
 * <p>tag 는 고정 집합만 쓴다. 역 이름을 tag 로 넣으면 시계열이 역 수만큼 늘어난다.
 */
@Component
@RequiredArgsConstructor
public class SubwayRealtimeMetrics {

    public static final String CACHE_REQUESTS = "subway.station.arrival.cache.requests";
    public static final String CACHE_ERRORS = "subway.station.arrival.cache.errors";
    public static final String SINGLEFLIGHT = "singleflight.requests";

    private static final String PROVIDER = "subway";
    private static final String TYPE = "station_arrival";

    private final MeterRegistry registry;

    /** result=hit/miss. */
    public void recordCacheAccess(String result) {
        registry.counter(CACHE_REQUESTS, "result", result).increment();
    }

    /** op=read/write. hit 비율을 오염시키지 않도록 장애를 따로 센다. */
    public void recordCacheError(String op) {
        registry.counter(CACHE_ERRORS, "op", op).increment();
    }

    /** joiner 수가 곧 막아낸 중복 호출 수다. 검색 쪽 지표와 같은 이름·tag 모양을 쓴다. */
    public void recordSingleFlight(SingleFlight.Role role) {
        registry.counter(SINGLEFLIGHT,
                "provider", PROVIDER,
                "type", TYPE,
                "role", role.name().toLowerCase()).increment();
    }
}
