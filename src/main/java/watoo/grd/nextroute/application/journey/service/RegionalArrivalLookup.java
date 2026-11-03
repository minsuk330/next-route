package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;
import watoo.grd.nextroute.application.arrival.port.out.GyeonggiBusArrivalPort;
import watoo.grd.nextroute.application.arrival.port.out.TagoBusArrivalPort;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.route.mapping.TagoCityCodeLookup;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 경기·TAGO 정류장에 <b>내 버스</b>가 언제 닿는지(#264 PR-4).
 *
 * <p>내 버스는 "몇 정류장 전"으로 고른다. 그 정류장까지 남은 순번이 {@code expectedAway} 인데, 버스가 지금 정류장을
 * 막 떠났으면 하나 적게 잡힌다 — 그래서 {@code [expectedAway-1, expectedAway]} 만 받는다. 그보다 멀면 <b>내 뒤의
 * 차</b>다. 받지 않는다(늦은 도착을 "아직 여유"로 말하면 사용자가 내릴 곳을 지나친다). TAGO 응답엔 차량번호가 없어
 * 정류장을 넘겨 가며 같은 차를 이어 잡지 못한다 — 순번만 쓴다.
 *
 * <p>재탐색 전용 일일 할당량(지역별)을 쓰고, 다 쓰면 {@link Lookup#quotaExhausted()} 다. 같은 정류장·노선은
 * 짧게 캐시한다(여정끼리 나눠 쓴다). 워커 한 대라 메모리 캐시로 충분하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegionalArrivalLookup {

    private final GyeonggiBusArrivalPort gyeonggiPort;
    private final TagoBusArrivalPort tagoPort;
    private final TagoCityCodeLookup cityCodeLookup;
    private final BusApiQuotaPort quotaPort;
    private final JourneyRerouteProperties properties;
    private final Clock clock;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public Lookup arrivalAt(RegionalBusProvider provider, String routeId, String stopId, int expectedAway) {
        Instant now = Instant.now(clock);
        Optional<List<Approach>> approaches = approaches(provider, routeId, stopId, now);
        if (approaches == null) {
            return Lookup.QUOTA;
        }
        if (approaches.isEmpty()) {
            return Lookup.BLOCKED;
        }
        return approaches.get().stream()
                .filter(approach -> approach.stopsAway() != null && approach.seconds() != null)
                .filter(approach -> approach.stopsAway() <= expectedAway
                        && approach.stopsAway() >= expectedAway - 1)
                .findFirst()
                .map(approach -> new Lookup(now.plusSeconds(approach.seconds()), null))
                .orElse(Lookup.NO_REALTIME);
    }

    /**
     * 그 정류장에 그 노선의 <b>다음 버스</b>가 언제 오는지 — 기다리는 단계(#333). "내 버스"를 고르지 않고 가장 이른
     * 도착을 쓴다. 할당량·캐시는 {@link #arrivalAt} 과 같다.
     */
    public Lookup nextArrival(RegionalBusProvider provider, String routeId, String stopId) {
        Instant now = Instant.now(clock);
        Optional<List<Approach>> approaches = approaches(provider, routeId, stopId, now);
        if (approaches == null) {
            return Lookup.QUOTA;
        }
        if (approaches.isEmpty()) {
            return Lookup.BLOCKED;
        }
        return approaches.get().stream()
                .map(Approach::seconds)
                .filter(seconds -> seconds != null && seconds >= 0)
                .min(Integer::compare)
                .map(seconds -> new Lookup(now.plusSeconds(seconds), null))
                .orElse(Lookup.NO_REALTIME);
    }

    /** @return null = 할당량 소진, 빈 Optional = 조회 실패 */
    private Optional<List<Approach>> approaches(RegionalBusProvider provider, String routeId, String stopId,
                                                Instant now) {
        String key = provider + ":" + stopId + ":" + routeId;
        Cached hit = cache.get(key);
        if (hit != null && hit.at().plusSeconds(properties.getBoard().getRegionalArrivalCacheSeconds()).isAfter(now)) {
            return Optional.of(hit.approaches());
        }
        JourneyRerouteProperties.Board board = properties.getBoard();
        boolean admitted = provider == RegionalBusProvider.GG
                ? quotaPort.tryAcquire("reroute-gg-arrival", board.getQuotaGyeonggi())
                : quotaPort.tryAcquire("reroute-tago-arrival", board.getQuotaTago());
        if (!admitted) {
            return null;
        }
        try {
            List<Approach> approaches = provider == RegionalBusProvider.GG
                    ? gyeonggi(routeId, stopId) : tago(routeId, stopId);
            if (approaches == null) {
                return Optional.empty();
            }
            cache.put(key, new Cached(now, approaches));
            return Optional.of(approaches);
        } catch (RuntimeException e) {
            log.warn("[RerouteBoard] 지역 도착 조회 실패 provider={} stop={}: {}", provider, stopId, e.getMessage());
            return Optional.empty();
        }
    }

    private List<Approach> gyeonggi(String routeId, String stopId) {
        var result = gyeonggiPort.getArrival(stopId, routeId, false);   // 재탐색 버킷을 위에서 이미 점유했다
        if (!result.isOk()) {
            return null;
        }
        List<Approach> approaches = new ArrayList<>();
        for (GyeonggiBusArrival arrival : result.data()) {
            approaches.add(new Approach(arrival.locationNo1(),
                    arrival.predictMin1() == null ? null : arrival.predictMin1() * 60));
            approaches.add(new Approach(arrival.locationNo2(),
                    arrival.predictMin2() == null ? null : arrival.predictMin2() * 60));
        }
        return approaches;
    }

    private List<Approach> tago(String routeId, String stopId) {
        Optional<String> cityCode = cityCodeLookup.cityCodeOfRoute(routeId);
        if (cityCode.isEmpty()) {
            return null;
        }
        var result = tagoPort.getArrivals(cityCode.get(), stopId, routeId);
        if (!result.isOk()) {
            return null;
        }
        List<Approach> approaches = new ArrayList<>();
        for (TagoBusArrival arrival : result.data()) {
            approaches.add(new Approach(arrival.stopsAway(), arrival.arrSeconds()));
        }
        return approaches;
    }

    private record Approach(Integer stopsAway, Integer seconds) {
    }

    private record Cached(Instant at, List<Approach> approaches) {
    }

    /** 도착 시각 또는 실패 사유(지표 태그). */
    public record Lookup(Instant at, String failureReason) {

        static final Lookup QUOTA = new Lookup(null, "quota-exhausted");
        static final Lookup BLOCKED = new Lookup(null, "arrival-blocked");
        static final Lookup NO_REALTIME = new Lookup(null, "no-realtime");

        public boolean failed() {
            return at == null;
        }

        public boolean quotaExhausted() {
            return QUOTA.failureReason().equals(failureReason);
        }
    }
}
