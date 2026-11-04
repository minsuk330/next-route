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
import java.util.regex.Pattern;

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

    /** TAGO 노선 id(영문 3글자 접두사). {@code BusArrivalQueryService} 와 같은 규칙. */
    private static final Pattern TAGO_ID = Pattern.compile("^[A-Z]{3}[\\dA-Z-]+$");
    /** 경기 GBIS 노선 id(2로 시작하는 9자리). 서울은 1로 시작해 겹치지 않는다({@code TransferStopResolver} 와 같은 규칙). */
    private static final Pattern GBIS_ROUTE_ID = Pattern.compile("^2\\d{8}$");

    private final GyeonggiBusArrivalPort gyeonggiPort;
    private final TagoBusArrivalPort tagoPort;
    private final TagoCityCodeLookup cityCodeLookup;
    private final BusApiQuotaPort quotaPort;
    private final JourneyRerouteProperties properties;
    private final Clock clock;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * 지역 실시간으로 물을 구간의 공급자. 서울 노선으로 풀리지 않았고 원천 노선 id 가 경기·TAGO 모양일 때만이다. 모양을 모르는 id
     * (서울 DB 에 없는 서울 노선, 시외버스 등)는 지역 API 로 묻지 않는다 — 엉뚱한 원천에 물어 할당량만 쓴다.
     *
     * @return 지역 구간이 아니면 null
     */
    public static RegionalBusProvider providerOf(String routeId, String sourceRouteId, String sourceStopId) {
        if ((routeId != null && !routeId.isBlank()) || sourceRouteId == null
                || sourceStopId == null || sourceStopId.isBlank()) {
            return null;
        }
        if (GBIS_ROUTE_ID.matcher(sourceRouteId).matches()) {
            return RegionalBusProvider.GG;
        }
        return TAGO_ID.matcher(sourceRouteId).matches() ? RegionalBusProvider.TAGO : null;
    }

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

    /**
     * 사용자가 준비를 마친 뒤 처음 오는 버스 — 갈아탈 차 판정. 먼저 지나가는 차는 탈 수 없어 고르지 않는다. 응답에 그런 차가 없으면
     * {@code no-realtime} 이다. 지난 차로 "환승 놓침"을 단정하지 않는다: 응답은 다음 한두 대뿐이라 그 뒤에 탈 차가 있을 수 있다.
     * 서울 실시간의 {@code SingleTransferPredictor.pickRealtime} 과 같은 규칙이다.
     */
    public Lookup boardableAt(RegionalBusProvider provider, String routeId, String stopId, Instant anchorAt) {
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
                .map(now::plusSeconds)
                .filter(at -> !at.isBefore(anchorAt))
                .min(Instant::compareTo)
                .map(at -> new Lookup(at, null))
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
