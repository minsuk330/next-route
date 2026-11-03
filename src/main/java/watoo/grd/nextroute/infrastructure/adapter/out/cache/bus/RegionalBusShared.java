package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.arrival.config.RegionalBusSharedProperties;
import watoo.grd.nextroute.application.arrival.port.out.RegionalBusCachePort;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.application.route.concurrent.CrossInstanceFlightRunner;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.CrossInstanceFlightPort;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.RedisNamedSemaphore;

import java.time.Duration;

/**
 * 경기·TAGO 어댑터가 서버 사이에서 공유하는 부품 묶음(bus-multi-instance PR-4): 공유 캐시, 서버 사이 single-flight, 일일 몫,
 * 이름 붙은 세마포어. {@link #enabled()} 가 false 면 어댑터는 기존 JVM 경로를 쓴다 — {@link #disabled()} 는 시험·구형 생성자용.
 */
@Component
public class RegionalBusShared {

    private final RegionalBusSharedProperties props;
    private final RegionalBusCachePort cache;
    private final CrossInstanceFlightPort flight;
    private final BusApiQuotaPort quota;
    private final RedisNamedSemaphore semaphore;
    private final RouteSearchMetrics metrics;

    @Autowired
    public RegionalBusShared(RegionalBusSharedProperties props, RegionalBusCachePort cache, CrossInstanceFlightPort flight,
                             BusApiQuotaPort quota, RedisNamedSemaphore semaphore, RouteSearchMetrics metrics) {
        this.props = props;
        this.cache = cache;
        this.flight = flight;
        this.quota = quota;
        this.semaphore = semaphore;
        this.metrics = metrics;
    }

    public static RegionalBusShared disabled() {
        return new RegionalBusShared(new RegionalBusSharedProperties(), null, null, null, null, null);
    }

    public boolean enabled() {
        return props.isEnabled();
    }

    public RegionalBusSharedProperties props() {
        return props;
    }

    public RegionalBusCachePort cache() {
        return cache;
    }

    public BusApiQuotaPort quota() {
        return quota;
    }

    public RedisNamedSemaphore semaphore() {
        return semaphore;
    }

    /** 공급자 이름(gg, tago)을 지표 태그로 하는 러너. */
    public CrossInstanceFlightRunner runner(String provider) {
        return new CrossInstanceFlightRunner(flight,
                (type, role) -> metrics.recordCrossInstanceFlight(provider, type, role),
                props.getPollMs(), Duration.ofMillis(Math.max(100, props.getResultTtlMs())));
    }
}
