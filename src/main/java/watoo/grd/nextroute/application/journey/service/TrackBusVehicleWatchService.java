package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Provider;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Snapshot;
import watoo.grd.nextroute.application.bus.port.out.BusVehiclePositionCachePort;
import watoo.grd.nextroute.application.journey.config.JourneyTrackProperties;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteStopStore;
import watoo.grd.nextroute.application.route.mapping.TagoCityCodeLookup;
import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyEventRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 단독 추적 버스의 <b>탄 차</b>를 찾는다(#265 PR-4). worker 의 스케줄러가 tick 마다 부른다.
 *
 * <h2>무엇을 보나 — DB 가 대상 목록이다</h2>
 * <p>진행 중인 {@code kind=TRACK} 여정 중 (a) 지금 버스를 기다리는 중이거나 (b) 버스에 탔는데 차량 판정이 아직 안 끝난
 * ({@code vehicle_matched_at IS NULL}) 것. 일반 여정은 보지 않는다(#265 결정). 대상 목록을 Redis 집합으로 따로 들지 않는
 * 이유: 시작·전이·종료마다 집합을 고쳐야 하고, 빠뜨리면 영원히 관찰하거나 영영 안 본다. DB 가 이미 그 답이다.
 *
 * <h2>관찰 — 기다리는 동안 내내</h2>
 * <p>버스를 기다리는 동안 그 노선 위치를 60초마다, 승차 정류장 2정거장 안에 차가 들어오면 15초마다 받는다. 대기 예측
 * ("5분 안")을 조건으로 쓰지 않는다 — 실시간이 없거나 할당량이 바닥나면 영영 관찰되지 않는다. 같은 노선을 보는 여정끼리
 * 조회를 나눠 쓴다. 연속 두 관측에서 승차 정류장을 떠난 차를 출발 사건으로 남긴다(노선·정류장별 최근 10분).
 *
 * <h2>대조 — 승차 시각 ±60초</h2>
 * <p>승차 시각 = 앱의 {@code WAIT_BUS → RIDE_BUS} 전이 명령의 기기 시각. 기기 시계가 서버 수신 시각과 2분 넘게 벌어지면
 * 서버 수신 시각. 창이 다 지나고 마지막 관측이 들어올 때까지(창 + 근접 간격) 기다렸다 고른다 — 창 안에 늦게 떠난 두 번째
 * 차를 못 보고 고르면 붙어 온 두 대를 하나로 착각한다. 0대면 30초 더 기다렸다 포기.
 *
 * <h2>쓰기</h2>
 * <p>차량 칼럼은 부분 UPDATE 만 한다({@code assignVehicle}) — 엔티티 저장은 이 칼럼을 쓰지 않는다. 판정이 끝나면
 * {@code vehicle_matched_at} 이 차서 다시 보지 않는다. 그래서 명령 재전송·중복 tick 이 대조를 두 번 하지 못한다.
 *
 * <p>상태(직전 관측·출발 사건)는 이 프로세스 메모리에 있다. worker 가 재시작하면 그 사이 출발 사건을 잃고, 그 탑승은
 * 차량 없이 추적된다(수용).
 */
@Slf4j
@Service
public class TrackBusVehicleWatchService {

    private static final String SOURCE_AUTO_MATCH = "AUTO_MATCH";

    private final JourneySessionRepository sessionRepository;
    private final JourneyLegRepository legRepository;
    private final JourneyLegRouteOptionRepository optionRepository;
    private final JourneyEventRepository eventRepository;
    private final RegionalBusRouteStopStore stopStore;
    private final TagoCityCodeLookup cityCodeLookup;
    private final BusPositionPort positionPort;
    private final BusApiQuotaPort quotaPort;
    private final BusVehiclePositionCachePort positionCache;
    private final JourneyTrackProperties properties;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate tx;
    private final Clock clock;

    private final Map<RouteKey, RouteState> states = new ConcurrentHashMap<>();
    private final Map<Long, Optional<RouteKey>> routeByLeg = new ConcurrentHashMap<>();

    public TrackBusVehicleWatchService(JourneySessionRepository sessionRepository, JourneyLegRepository legRepository,
                                       JourneyLegRouteOptionRepository optionRepository,
                                       JourneyEventRepository eventRepository, RegionalBusRouteStopStore stopStore,
                                       TagoCityCodeLookup cityCodeLookup, BusPositionPort positionPort,
                                       BusApiQuotaPort quotaPort, BusVehiclePositionCachePort positionCache,
                                       JourneyTrackProperties properties, MeterRegistry meterRegistry,
                                       PlatformTransactionManager transactionManager, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.legRepository = legRepository;
        this.optionRepository = optionRepository;
        this.eventRepository = eventRepository;
        this.stopStore = stopStore;
        this.cityCodeLookup = cityCodeLookup;
        this.positionPort = positionPort;
        this.quotaPort = quotaPort;
        this.positionCache = positionCache;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 원천 노선 하나 + 승차 정류장 순번. 같은 키를 보는 여정끼리 조회·사건을 나눠 쓴다. */
    record RouteKey(Provider provider, String routeId, String cityCode) {
    }

    /** 관찰 대상 하나. {@code pending} 이면 탔고 차량 판정을 기다린다. */
    record Target(JourneySession session, JourneyLeg ride, RouteKey route, int boardSeq, boolean pending) {
    }

    /** 노선별 관찰 상태. 직전 관측과 승차 정류장 순번별 출발 사건. */
    static final class RouteState {
        Snapshot previous;
        Instant nextPollAt = Instant.EPOCH;
        Instant lastSeenAt = Instant.EPOCH;
        boolean budgetExhausted;
        final Map<Integer, List<BusVehicleMatcher.Departure>> departures = new ConcurrentHashMap<>();
    }

    /** @return 이번 tick 에 본 대상 수 */
    public int tick() {
        Instant now = Instant.now(clock);
        List<Target> targets = collectTargets();
        Map<RouteKey, List<Target>> byRoute = new LinkedHashMap<>();
        for (Target target : targets) {
            byRoute.computeIfAbsent(target.route(), k -> new ArrayList<>()).add(target);
        }
        for (Map.Entry<RouteKey, List<Target>> entry : byRoute.entrySet()) {
            RouteState state = states.computeIfAbsent(entry.getKey(), k -> new RouteState());
            state.lastSeenAt = now;
            boolean pending = entry.getValue().stream().anyMatch(Target::pending);
            if (!now.isBefore(state.nextPollAt)) {
                poll(entry.getKey(), entry.getValue(), state, pending, now);
            }
            for (Target target : entry.getValue()) {
                if (target.pending()) {
                    decide(target, state, now);
                }
            }
        }
        prune(now);
        return targets.size();
    }

    // ── 대상 ─────────────────────────────────────────────────────────────────

    List<Target> collectTargets() {
        List<Target> targets = new ArrayList<>();
        for (JourneySession session : sessionRepository.findByKindAndStatusAndDeletedAtIsNull(
                JourneyKind.TRACK, JourneyStatus.ACTIVE)) {
            List<JourneyLeg> legs = legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                    session.getId(), session.getRouteRevision());
            JourneyLeg current = legAt(legs, session.getCurrentLegIndex());
            if (current == null) {
                continue;
            }
            JourneyLeg wait;
            JourneyLeg ride;
            boolean pending;
            if (current.getType() == JourneyLegType.WAIT_BUS) {
                wait = current;
                ride = legAt(legs, current.getLegIndex() + 1);
                pending = false;
            } else if (current.getType() == JourneyLegType.RIDE_BUS && current.getVehicleMatchedAt() == null) {
                ride = current;
                wait = legAt(legs, current.getLegIndex() - 1);
                pending = true;
            } else {
                continue;
            }
            if (ride == null || ride.getType() != JourneyLegType.RIDE_BUS || wait == null) {
                continue;
            }
            JourneyLeg rideLeg = ride;
            JourneyLeg waitLeg = wait;
            Optional<RouteKey> route = routeByLeg.computeIfAbsent(rideLeg.getId(), id -> resolveRoute(waitLeg, rideLeg));
            Integer boardSeq = route.map(r -> boardSeq(r, waitLeg, rideLeg)).orElse(null);
            if (route.isEmpty() || boardSeq == null) {
                continue;
            }
            targets.add(new Target(session, ride, route.get(), boardSeq, pending));
        }
        return targets;
    }

    private Optional<RouteKey> resolveRoute(JourneyLeg wait, JourneyLeg ride) {
        Optional<JourneyLegRouteOption> option = Optional.ofNullable(wait.getSelectedRouteOptionId())
                .flatMap(optionRepository::findById);
        if (option.isEmpty()) {
            return Optional.empty();
        }
        JourneyLegRouteOption o = option.get();
        if (o.getRouteId() != null && o.getBoardingSeq() != null) {
            return Optional.of(new RouteKey(Provider.SEOUL, o.getRouteId(), null));
        }
        String routeId = o.getSourceRouteId();
        if (routeId == null || routeId.isBlank()) {
            return Optional.empty();
        }
        if (!stopStore.findRouteStops(RegionalBusProvider.GG, routeId).isEmpty()) {
            return Optional.of(new RouteKey(Provider.GYEONGGI, routeId, null));
        }
        if (!stopStore.findRouteStops(RegionalBusProvider.TAGO, routeId).isEmpty()) {
            return cityCodeLookup.cityCodeOfRoute(routeId).map(city -> new RouteKey(Provider.TAGO, routeId, city));
        }
        return Optional.empty();
    }

    /** 승차 정류장의 실시간 순번. 서울 = 보강된 {@code boardingSeq}, 경기·TAGO = #267 표에서 승차 정류장 첫 등장. */
    private Integer boardSeq(RouteKey route, JourneyLeg wait, JourneyLeg ride) {
        if (route.provider() == Provider.SEOUL) {
            return Optional.ofNullable(wait.getSelectedRouteOptionId()).flatMap(optionRepository::findById)
                    .map(JourneyLegRouteOption::getBoardingSeq).orElse(null);
        }
        String stopId = ride.getSourceStartStopId() != null ? ride.getSourceStartStopId() : wait.getSourceStartStopId();
        if (stopId == null) {
            return null;
        }
        RegionalBusProvider provider = route.provider() == Provider.GYEONGGI
                ? RegionalBusProvider.GG : RegionalBusProvider.TAGO;
        for (RegionalRouteStop stop : stopStore.findRouteStops(provider, route.routeId())) {
            if (stopId.equals(stop.stopId())) {
                return stop.seq();
            }
        }
        return null;
    }

    // ── 관찰 ─────────────────────────────────────────────────────────────────

    private void poll(RouteKey route, List<Target> targets, RouteState state, boolean pending, Instant now) {
        JourneyTrackProperties.Bus bus = properties.getBus();
        if (!quotaPort.tryAcquire(bucket(route.provider()), budget(route.provider()))) {
            state.budgetExhausted = true;
            state.nextPollAt = now.plusSeconds(bus.getIdlePollSeconds());
            recordPosition(route.provider(), "budget");
            return;
        }
        state.budgetExhausted = false;
        Snapshot snapshot = positionPort.positions(route.provider(), route.routeId(), route.cityCode());
        recordPosition(route.provider(), snapshot.outcome().name().toLowerCase());
        if (!snapshot.isOk()) {
            state.nextPollAt = now.plusSeconds(bus.getNearPollSeconds());
            return;
        }
        positionCache.put(route.provider(), route.routeId(), snapshot);
        boolean near = pending;
        for (Integer boardSeq : targets.stream().map(Target::boardSeq).distinct().toList()) {
            List<BusVehicleMatcher.Departure> found = BusDepartureDetector.departures(
                    state.previous, snapshot, boardSeq, bus.getNearStops() + 1);
            if (!found.isEmpty()) {
                state.departures.computeIfAbsent(boardSeq, k -> new ArrayList<>()).addAll(found);
            }
            near |= BusDepartureDetector.approaching(snapshot, boardSeq, bus.getNearStops());
        }
        state.previous = snapshot;
        state.nextPollAt = now.plusSeconds(near ? bus.getNearPollSeconds() : bus.getIdlePollSeconds());
    }

    // ── 대조 ─────────────────────────────────────────────────────────────────

    private void decide(Target target, RouteState state, Instant now) {
        JourneyTrackProperties.Bus bus = properties.getBus();
        Instant boardedAt = boardedAt(target);
        Duration window = Duration.ofSeconds(bus.getMatchWindowSeconds());
        Instant decideAt = boardedAt.plus(window).plusSeconds(bus.getNearPollSeconds());
        if (now.isBefore(decideAt)) {
            return;
        }
        BusVehicleMatcher.Result result = BusVehicleMatcher.match(boardedAt,
                state.departures.getOrDefault(target.boardSeq(), List.of()), window,
                Duration.ofSeconds(bus.getAmbiguityGapSeconds()));
        switch (result.kind()) {
            case MATCHED -> {
                BusVehicleMatcher.Departure d = result.departure();
                String idType = target.route().provider() == Provider.TAGO ? "PLAIN_NO" : "VEH_ID";
                Integer written = tx.execute(s -> legRepository.assignVehicle(target.ride().getId(), d.vehicleId(),
                        idType, SOURCE_AUTO_MATCH, now));
                if (written != null && written > 0) {
                    recordMatch(target.route().provider(), "matched");
                    log.info("[TrackBus] 차량 고정 journeyId={} legIndex={} provider={}",
                            target.session().getId(), target.ride().getLegIndex(), target.route().provider());
                }
            }
            case AMBIGUOUS -> finish(target, now, "ambiguous");
            case NONE -> {
                if (state.budgetExhausted) {
                    finish(target, now, "budget");
                } else if (!now.isBefore(decideAt.plusSeconds(bus.getRetryAfterSeconds()))) {
                    finish(target, now, "none");
                }
            }
        }
    }

    private void finish(Target target, Instant now, String reason) {
        Integer written = tx.execute(s -> legRepository.finishVehicleUnmatched(target.ride().getId(), now));
        if (written != null && written > 0) {
            recordMatch(target.route().provider(), reason);
        }
    }

    /**
     * 승차 시각. 탑승 단계로 넘어간 적용된 전이 명령의 기기 시각 — 서버 수신과 {@code maxClockSkew} 넘게 벌어지면 서버
     * 수신 시각. 명령을 못 찾으면 단계 활성 시각(서버 적용 시각).
     */
    Instant boardedAt(Target target) {
        JourneyLeg ride = target.ride();
        Duration maxSkew = Duration.ofSeconds(properties.getBus().getMaxClockSkewSeconds());
        return eventRepository.findAppliedTransitionInto(ride.getJourneyId(), ride.getRouteRevision(),
                        ride.getLegIndex())
                .map(t -> {
                    if (t.getClientOccurredAt() == null) {
                        return t.getServerReceivedAt();
                    }
                    Duration skew = Duration.between(t.getClientOccurredAt(), t.getServerReceivedAt()).abs();
                    return skew.compareTo(maxSkew) > 0 ? t.getServerReceivedAt() : t.getClientOccurredAt();
                })
                .orElseGet(() -> ride.getActivatedAt() != null ? ride.getActivatedAt() : Instant.now(clock));
    }

    // ── 정리 ─────────────────────────────────────────────────────────────────

    private void prune(Instant now) {
        Instant cutoff = now.minusSeconds(properties.getBus().getEventRetentionSeconds());
        states.entrySet().removeIf(e -> e.getValue().lastSeenAt.isBefore(cutoff));
        for (RouteState state : states.values()) {
            state.departures.values().forEach(list -> list.removeIf(d -> d.departedAt().isBefore(cutoff)));
        }
        if (routeByLeg.size() > 10_000) {
            routeByLeg.clear();
        }
    }

    private static JourneyLeg legAt(List<JourneyLeg> legs, int index) {
        return legs.stream().filter(leg -> leg.getLegIndex() == index).findFirst().orElse(null);
    }

    private static String bucket(Provider provider) {
        return "track-bus-pos-" + provider.name().toLowerCase();
    }

    private int budget(Provider provider) {
        JourneyTrackProperties.Budget budget = properties.getBus().getPositionBudget();
        return switch (provider) {
            case SEOUL -> budget.getSeoul();
            case GYEONGGI -> budget.getGyeonggi();
            case TAGO -> budget.getTago();
        };
    }

    private void recordPosition(Provider provider, String result) {
        meterRegistry.counter("bus.position", "provider", provider.name(), "result", result).increment();
    }

    private void recordMatch(Provider provider, String result) {
        meterRegistry.counter("bus.match", "provider", provider.name(), "result", result).increment();
    }
}
