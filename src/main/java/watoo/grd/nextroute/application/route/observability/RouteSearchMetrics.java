package watoo.grd.nextroute.application.route.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 경로 검색 관측 지표. 모든 tag 는 고정 집합(저카디널리티)이며,
 * 좌표·노선 ID·requestId 등 요청별로 달라지는 값은 tag 로 쓰지 않는다(PLAN.md §5).
 */
@Component
@RequiredArgsConstructor
public class RouteSearchMetrics {

    public static final String SEARCH_DURATION = "route.search.duration";
    public static final String STAGE_DURATION = "route.search.stage.duration";
    public static final String DEGRADED = "route.search.degraded";
    public static final String DEADLINE_EXCEEDED = "route.search.deadline.exceeded";
    public static final String EXTERNAL_CALLS = "external.api.calls";
    public static final String EXTERNAL_DURATION = "external.api.duration";
    public static final String CACHE_REQUESTS = "bus.realtime.cache.requests";
    public static final String CACHE_ERRORS = "bus.realtime.cache.errors";

    private final MeterRegistry registry;

    /** 요청 전체 소요와 완전성. */
    public void recordSearch(SearchOutcome outcome, long nanos) {
        timer(SEARCH_DURATION, "outcome", outcome.tag()).record(nanos, TimeUnit.NANOSECONDS);
    }

    /** 단계별 소요. outcome 은 해당 단계의 성공/생략 여부. */
    public void recordStage(SearchComponent component, boolean degraded, long nanos) {
        Timer.builder(STAGE_DURATION)
                .tag("stage", component.tag())
                .tag("outcome", degraded ? "degraded" : "ok")
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** 시간 예산을 초과한 지점. 어느 단계가 예산을 다 쓰는지 알려준다. */
    public void recordDeadlineExceeded(SearchComponent component) {
        registry.counter(DEADLINE_EXCEEDED, "stage", component.tag()).increment();
    }

    /** 요청 종료 시 누적된 degradation 을 사유별로 기록. */
    public void recordDegradations(Map<SearchComponent, DegradeReason> degradations) {
        degradations.forEach((component, reason) ->
                registry.counter(DEGRADED, "component", component.tag(), "reason", reason.tag()).increment());
    }

    /**
     * 실제 provider 호출 1건. 캐시 hit 으로 호출이 생략된 경우는 여기 집계하지 않는다.
     *
     * @param attempts 이 호출에 쓰인 시도 횟수(재시도 포함). retry tag 로 재시도 여부만 남긴다.
     */
    public void recordExternalCall(String provider, String outcome, int attempts, long nanos) {
        registry.counter(EXTERNAL_CALLS,
                "provider", provider,
                "outcome", outcome,
                "retry", attempts > 1 ? "true" : "false").increment();
        Timer.builder(EXTERNAL_DURATION)
                .tag("provider", provider)
                .tag("outcome", outcome)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 검색 경로의 실시간 버스 캐시 조회 결과. type=arrival/position, result=hit/miss.
     * hit ratio 의 분모가 되므로 <b>검색 경로만</b> 집계한다. Redis 장애로 인한 read 실패는
     * 검색 입장에서 miss 이며, 그 원인은 {@link #recordCacheError} 로 따로 남긴다.
     */
    public void recordCacheAccess(String type, String result) {
        registry.counter(CACHE_REQUESTS, "type", type, "result", result).increment();
    }

    /**
     * 캐시 자체의 read/write 실패(Redis 장애 등). hit/miss 비율을 오염시키지 않도록 별도 지표로 둔다.
     * 검색·수집기 등 이 캐시의 모든 소비자를 포함한다. op=read/write.
     */
    public void recordCacheError(String type, String op) {
        registry.counter(CACHE_ERRORS, "type", type, "op", op).increment();
    }

    private Timer timer(String name, String tagKey, String tagValue) {
        return Timer.builder(name).tag(tagKey, tagValue).register(registry);
    }
}
