package watoo.grd.nextroute.application.journey.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyTrackProperties;
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;
import watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPattern;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternDetail;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternUnavailableException;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 단독 추적(#265)의 OTP 패턴 원천 앞단: <b>동시성 permit + 캐시</b>.
 *
 * <p>한 요청이 OTP 를 여러 번 부른다(정류장 패턴 → 패턴 상세 → trip → 정차 시각). permit 하나와 마감 하나를 그 묶음
 * 전체가 나눠 쓴다({@link #withPermit}) — 호출마다 잡으면 검색과 permit 을 다투다 중간에서 끊긴다. 마감은 검색과 같은
 * {@code route.search.otp-ms}.
 *
 * <p>캐시는 정류장 패턴·패턴 상세만, 프로세스 메모리에 {@code patternCacheSeconds}(기본 10분). <b>키에 그래프 버전을
 * 넣는다</b> — 패턴 코드는 그래프를 다시 빌드하면 바뀐다(P3 형상·GBIS 그래프로 자주 바뀜). trip·정차 시각은 운행일·시각
 * 따라 달라 캐시하지 않는다.
 */
@Slf4j
@Component
public class TrackPatternSource {

    private static final int CACHE_MAX_ENTRIES = 2_000;

    private final TransitPatternPort port;
    private final OtpConcurrencyGate gate;
    private final OtpRoutingProperties otpProperties;
    private final RouteSearchDeadlineProperties deadlineProperties;
    private final JourneyTrackProperties trackProperties;
    private final Clock clock;

    private final Map<String, Cached<List<TransitPattern>>> patternsAtStop = new ConcurrentHashMap<>();
    private final Map<String, Cached<TransitPatternDetail>> details = new ConcurrentHashMap<>();

    public TrackPatternSource(TransitPatternPort port, OtpConcurrencyGate gate,
                              OtpRoutingProperties otpProperties,
                              RouteSearchDeadlineProperties deadlineProperties,
                              JourneyTrackProperties trackProperties, Clock clock) {
        this.port = port;
        this.gate = gate;
        this.otpProperties = otpProperties;
        this.deadlineProperties = deadlineProperties;
        this.trackProperties = trackProperties;
        this.clock = clock;
    }

    /**
     * permit 하나로 OTP 묶음 호출을 돈다. 포화·장애·시간 초과는 전부 503 {@code TRACK_PATTERN_UNAVAILABLE}.
     */
    public <T> T withPermit(Function<RoutingDeadline, T> work) {
        RoutingDeadline deadline = RoutingDeadline.of(clock, deadlineProperties.getOtpMs());
        Optional<OtpPermit> permit = gate.tryAcquire(deadline, "api");
        if (permit.isEmpty()) {
            throw new BusinessException(JourneyErrorCode.TRACK_PATTERN_UNAVAILABLE);
        }
        try {
            return work.apply(deadline);
        } catch (TransitPatternUnavailableException e) {
            log.warn("[Track] OTP 패턴 조회 실패: {}", e.getMessage());
            throw new BusinessException(JourneyErrorCode.TRACK_PATTERN_UNAVAILABLE);
        } finally {
            gate.release(permit.get());
        }
    }

    public TransitPatternPort port() {
        return port;
    }

    public List<TransitPattern> patternsAtStop(String gtfsStopId, RoutingDeadline deadline) {
        String key = key(gtfsStopId);
        Cached<List<TransitPattern>> hit = patternsAtStop.get(key);
        if (hit != null && hit.fresh(clock.instant())) {
            return hit.value();
        }
        List<TransitPattern> patterns = port.patternsAtStop(gtfsStopId, deadline);
        put(patternsAtStop, key, patterns);
        return patterns;
    }

    /** 낡은 코드(빈 결과)는 캐시하지 않는다. */
    public Optional<TransitPatternDetail> pattern(String patternCode, RoutingDeadline deadline) {
        String key = key(patternCode);
        Cached<TransitPatternDetail> hit = details.get(key);
        if (hit != null && hit.fresh(clock.instant())) {
            return Optional.of(hit.value());
        }
        Optional<TransitPatternDetail> detail = port.pattern(patternCode, deadline);
        detail.ifPresent(d -> put(details, key, d));
        return detail;
    }

    public String graphVersion() {
        return otpProperties.getGraphVersion();
    }

    private String key(String id) {
        return otpProperties.getGraphVersion() + "|" + id;
    }

    private <V> void put(Map<String, Cached<V>> cache, String key, V value) {
        if (cache.size() >= CACHE_MAX_ENTRIES) {
            // 상한을 넘으면 통째로 비운다 — 정류장 수가 이 상한을 넘을 만큼 동시에 고르는 일은 드물다.
            cache.clear();
        }
        cache.put(key, new Cached<>(value,
                clock.instant().plusSeconds(trackProperties.getPatternCacheSeconds())));
    }

    private record Cached<V>(V value, Instant expiresAt) {
        boolean fresh(Instant now) {
            return now.isBefore(expiresAt);
        }
    }
}
