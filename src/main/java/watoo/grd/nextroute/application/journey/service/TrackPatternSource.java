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
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStopTime;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitTripRef;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
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
 * <p>캐시는 정류장 패턴·패턴 상세·형제 정류장·승하차 가능 여부만, 프로세스 메모리에 {@code patternCacheSeconds}(기본 10분). <b>키에 그래프 버전을
 * 넣는다</b> — 패턴 코드는 그래프를 다시 빌드하면 바뀐다(P3 형상·GBIS 그래프로 자주 바뀜). trip·정차 시각은 운행일·시각
 * 따라 달라 캐시하지 않는다.
 */
@Slf4j
@Component
public class TrackPatternSource {

    private static final int CACHE_MAX_ENTRIES = 2_000;
    private static final ZoneId FEED_ZONE = ZoneId.of("Asia/Seoul");

    private final TransitPatternPort port;
    private final OtpConcurrencyGate gate;
    private final OtpRoutingProperties otpProperties;
    private final RouteSearchDeadlineProperties deadlineProperties;
    private final JourneyTrackProperties trackProperties;
    private final Clock clock;

    private final Map<String, Cached<List<TransitPattern>>> patternsAtStop = new ConcurrentHashMap<>();
    private final Map<String, Cached<TransitPatternDetail>> details = new ConcurrentHashMap<>();
    private final Map<String, Cached<List<String>>> siblings = new ConcurrentHashMap<>();
    private final Map<String, Cached<List<TransitStopTime>>> stopAccess = new ConcurrentHashMap<>();

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

    /**
     * 피드별로 쪼개진 같은 정류장({@link TrackStopSiblings}) 중 그래프에 실제로 있고 가까운 것. 자기 자신은 빠진다.
     * 수도권 버스 정류장이 아니면 OTP 를 부르지 않는다.
     */
    public List<String> siblings(String gtfsStopId, RoutingDeadline deadline) {
        List<String> candidates = TrackStopSiblings.candidates(gtfsStopId);
        if (candidates.isEmpty()) {
            return List.of();
        }
        String key = key(gtfsStopId);
        Cached<List<String>> hit = siblings.get(key);
        if (hit != null && hit.fresh(clock.instant())) {
            return hit.value();
        }
        List<String> ids = new ArrayList<>(candidates.size() + 1);
        ids.add(gtfsStopId);
        ids.addAll(candidates);
        List<TransitStop> stops = port.stops(ids, deadline);
        List<String> found = stops.stream()
                .filter(stop -> stop.gtfsId().equals(gtfsStopId))
                .findFirst()
                .map(origin -> TrackStopSiblings.within(origin, stops.stream()
                        .sorted(Comparator.comparingInt(stop -> candidates.indexOf(stop.gtfsId())))
                        .toList()))
                .orElse(List.of());
        put(siblings, key, found);
        return found;
    }

    /**
     * 그 노선을 실제로 타는 정류장. {@code fromStopId} 가 그 노선을 지나면 그대로, 아니면 그 노선이 지나는 형제
     * 정류장이다. 어디에도 없으면 {@code fromStopId} 그대로(뒤따르는 선택이 422 를 낸다).
     *
     * <p>노선 목록은 형제 정류장 노선까지 합쳐 보여 주므로, 구버전 앱은 누른 정류장 id 로 다른 피드 노선을 고른다.
     */
    public String boardingStop(String routeGtfsId, String fromStopId, RoutingDeadline deadline) {
        if (passes(routeGtfsId, patternsAtStop(fromStopId, deadline))) {
            return fromStopId;
        }
        for (String sibling : siblings(fromStopId, deadline)) {
            if (passes(routeGtfsId, patternsAtStop(sibling, deadline))) {
                return sibling;
            }
        }
        return fromStopId;
    }

    private static boolean passes(String routeGtfsId, List<TransitPattern> patterns) {
        return patterns.stream().anyMatch(p -> TrackPatternSelector.routeMatches(routeGtfsId, p.route().gtfsId()));
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

    /**
     * 패턴 정류장마다 타고 내릴 수 있는지({@link TransitStopTime#pickup}·{@link TransitStopTime#dropoff}). 패턴 정류장과
     * 같은 순서·개수다.
     *
     * <p>OTP 는 정류장별 승하차 가능 여부까지 같은 trip 끼리만 한 패턴으로 묶는다 — trip 하나의 정차 시각이 패턴 전체의
     * 답이다. 그래서 시각과 달리 캐시한다. 오늘·어제 운행이 없거나 정류장 수가 패턴과 다르면 비어 있다(부르는 쪽은 거르지
     * 않는다 — 시작이 시간표 없음으로 막는다).
     */
    public Optional<List<TransitStopTime>> stopAccess(TransitPattern pattern, RoutingDeadline deadline) {
        String key = key(pattern.code());
        Cached<List<TransitStopTime>> hit = stopAccess.get(key);
        if (hit != null && hit.fresh(clock.instant())) {
            return Optional.of(hit.value());
        }
        LocalDate today = clock.instant().atZone(FEED_ZONE).toLocalDate();
        for (LocalDate date : List.of(today, today.minusDays(1))) {
            List<TransitTripRef> trips = port.tripsForDate(pattern.code(), date, deadline);
            if (trips.isEmpty()) {
                continue;
            }
            List<TransitStopTime> times = port.tripStopTimes(trips.get(0).gtfsId(), date, deadline);
            if (times.size() != pattern.stops().size()) {
                return Optional.empty();
            }
            put(stopAccess, key, times);
            return Optional.of(times);
        }
        return Optional.empty();
    }

    public boolean expired(RoutingDeadline deadline) {
        return deadline.expired(clock);
    }

    /** 지금부터 {@code budgetMs} 와 {@code outer} 중 이른 마감. 묶음 예산을 늘리지 않고 한 단계만 더 짧게 끊는다. */
    public RoutingDeadline capped(RoutingDeadline outer, long budgetMs) {
        RoutingDeadline cap = RoutingDeadline.of(clock, budgetMs);
        return cap.deadlineAt().isBefore(outer.deadlineAt()) ? cap : outer;
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
