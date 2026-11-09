package watoo.grd.nextroute.application.route.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import watoo.grd.nextroute.application.route.concurrent.SingleFlight;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
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
    public static final String ML_CACHE_REQUESTS = "ml.prediction.cache.requests";
    public static final String ML_CACHE_ERRORS = "ml.prediction.cache.errors";
    public static final String SINGLEFLIGHT = "singleflight.requests";
    public static final String CROSS_FLIGHT = "crossflight.requests";
    public static final String BULKHEAD_ACTIVE = "bulkhead.active";
    public static final String BULKHEAD_QUEUE = "bulkhead.queue";
    public static final String BULKHEAD_REJECTED = "bulkhead.rejected";
    public static final String HISTORY_DROPPED = "route.search.history.dropped";
    public static final String REGIONAL_WAIT = "route.search.regional.wait";

    private final MeterRegistry registry;

    /**
     * 검색 중 경기·TAGO 도착 조회(search-regional-bus-wait PR-2). provider=GG|TAGO, outcome=ok|limited|error|
     * limited_quota|limited_pool|abandoned(검색이 기한 안에 못 받음). 고정 집합.
     */
    public void recordRegionalWait(String provider, String outcome) {
        registry.counter(REGIONAL_WAIT, "provider", provider, "outcome", outcome).increment();
    }

    /** 검색 이력 저장이 유실된 횟수(큐 포화 또는 워커 저장 실패). best-effort 저장의 drop 관측. */
    public void recordHistoryDropped() {
        registry.counter(HISTORY_DROPPED).increment();
    }

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
     * ML 예측 캐시 조회 결과. result=hit/miss.
     *
     * <p>버스 캐시와 지표를 나눈 이유 — 두 캐시는 무효화 방식이 다르다. 버스는 TTL 로 늙고, ML 은
     * 입력이 바뀌면 키가 바뀌어 죽는다. 한 지표에 섞으면 hit 비율이 낮을 때 어느 쪽 문제인지 모른다.
     */
    public void recordMlCacheAccess(String result) {
        registry.counter(ML_CACHE_REQUESTS, "result", result).increment();
    }

    /** ML 예측 캐시 자체의 read/write 실패(Redis 장애 등). op=read/write. */
    public void recordMlCacheError(String op) {
        registry.counter(ML_CACHE_ERRORS, "op", op).increment();
    }

    /** provider 전용 풀이 포화되어 보강을 생략한 횟수. 실패가 아니라 관측 가능한 degradation 이다. */
    public void recordBulkheadRejected(String provider) {
        registry.counter(BULKHEAD_REJECTED, "provider", provider).increment();
    }

    /** provider 전용 풀의 현재 실행/대기 수를 gauge 로 노출. 부팅 시 1회 등록한다. */
    public void registerBulkheadGauges(String provider, ThreadPoolExecutor executor) {
        Gauge.builder(BULKHEAD_ACTIVE, executor, ThreadPoolExecutor::getActiveCount)
                .tag("provider", provider).register(registry);
        Gauge.builder(BULKHEAD_QUEUE, executor, e -> e.getQueue().size())
                .tag("provider", provider).register(registry);
    }

    /**
     * 서버 사이 single-flight(PR-2). role = leader / leader_recheck_hit / waiter_result / waiter_fallback /
     * waiter_takeover / waiter_timeout / redis_error. 고정 집합.
     */
    public void recordCrossInstanceFlight(String provider, String type, String role) {
        registry.counter(CROSS_FLIGHT, "provider", provider, "type", type, "role", role).increment();
    }

    /**
     * single-flight 참여 결과. owner 만 실제 provider 를 호출했고 joiner 는 그 결과를 공유받았다.
     * 따라서 joiner 수가 곧 "막아낸 중복 호출 수"다.
     */
    public void recordSingleFlight(String provider, String type, SingleFlight.Role role) {
        registry.counter(SINGLEFLIGHT,
                "provider", provider,
                "type", type,
                "role", role.name().toLowerCase()).increment();
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
