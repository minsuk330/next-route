package watoo.grd.nextroute.application.subway.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.concurrent.SingleFlight;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

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
    public static final String COLLECT_CYCLE = "subway.realtime.collect.duration";

    /*
     * 수집 한 바퀴 경계. 수집 주기(위치 5초·도착 15초)를 넘기는지가 판단 기준이라 두 주기를
     * 경계에 넣는다.
     */
    private static final Duration[] COLLECT_SLO = {
            Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofMillis(2_500), Duration.ofSeconds(5), Duration.ofSeconds(10),
            Duration.ofSeconds(15), Duration.ofSeconds(30)};

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

    /**
     * 수집기 한 바퀴(외부 호출 + 캐시·기록). 주기보다 길어지면 다음 바퀴가 그만큼 밀려 데이터가
     * 낡는다.
     *
     * @param collector {@code arrival} · {@code position}
     * @param scope     위치 수집은 {@code full}(전 노선) · {@code active}(링 도는 노선만),
     *                  도착 수집은 {@code all}
     */
    public void recordCollectCycle(String collector, String scope, long nanos) {
        Timer.builder(COLLECT_CYCLE).tag("collector", collector).tag("scope", scope)
                .serviceLevelObjectives(COLLECT_SLO)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }
}
