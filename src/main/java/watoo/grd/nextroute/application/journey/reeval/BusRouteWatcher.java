package watoo.grd.nextroute.application.journey.reeval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheWritePort;
import watoo.grd.nextroute.application.journey.observability.JourneyReevalMetrics;
import watoo.grd.nextroute.common.lock.LeaderLease;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 진행 중 여정이 기다리는 서울 버스 노선을 노선 단위로 감시해, 버스가 움직이면 그 정류장을 지켜보는 여정의
 * 다음 평가를 앞당긴다(#415-3 PR-B).
 *
 * <h2>왜 노선 단위인가</h2>
 * 노선 도착 API 한 번이면 그 노선의 모든 정류장이 온다. 여정마다·정류장마다 부르면 호출이 사용자 수에
 * 비례해 끝없이 늘지만, 노선 단위면 서울 노선 수(약 600)에서 멈춘다. 같은 감지 주기 기준으로 정류장 단위
 * 폴링의 0.6배(동시 여정 2,000) ~ 0.16배(20,000)다(COST-MODEL.md).
 *
 * <h2>얼마나 자주</h2>
 * 노선마다 지켜보는 여정 중 가장 급한 등급의 주기(긴급 10초·주의 15초·여유 60초). 10초보다 자주 부르지
 * 않는다 — 공급자 갱신 주기 실측 중앙값이 9초라 더 불러도 새 데이터가 없다. 급한 노선·구독이 많은 노선부터
 * 부르고, 전용 일일 몫({@code bus-watcher-daily-limit})이 떨어지면 멈춘다. 그때부터는 평가기의 간격
 * 폴링이 안전망이다.
 *
 * <h2>무엇을 변화로 보나</h2>
 * 정류장·순번마다 (첫 차량, 첫 차량이 있는 구간 순번, 상태, 둘째 차량). 버스가 한 정류장 다가오거나, 앞
 * 차가 지나가 다음 차가 첫째가 되거나, 곧 도착·운행 종료로 바뀔 때만 달라진다. 도착 예정 초는 넣지 않는다
 * — 실측에서 정류장별 값이 중앙값 10초마다 흔들려, 넣으면 거의 모든 조회가 이벤트가 된다.
 *
 * <h2>부수 효과</h2>
 * 받은 응답을 정류장·순번별로 쪼개 평가기가 읽는 도착 캐시에 채운다(그림자 모드가 아닐 때). 그러면 깨어난
 * 평가기가 공급자를 다시 부르지 않고 방금 받은 값을 읽는다.
 *
 * <p>worker 전용, 리더 한 대만 돈다. 노선 조회는 재시도 없이 짧은 기한(1.5초)으로 하고, tick 하나가 쓰는
 * 시간에 상한(5초)을 둔다 — 공용 스케줄러 스레드를 붙잡지 않게. 연달아 세 번 비거나 실패한 노선은 서울 노선이
 * 아닌 것(경기·지방)으로 보고 한동안 건너뛴다 — 그 키는 평가기의 간격 폴링으로만 본다.
 *
 * <p><b>한도 규칙</b>: 감시자 몫({@code bus-watcher-daily-limit}) + 수집기 몫 + 검색 몫 ≤ 공급자 키의 일일
 * 한도. 감시자 몫은 따로 세지만 공급자 키와 code 7 차단기는 모두가 같이 쓴다 — 넘기면 수집·검색까지 자정까지
 * 멈춘다.
 */
@Slf4j
@Component
@Profile("worker")
public class BusRouteWatcher {

    static final String SOURCE = "bus";
    static final String QUOTA_BUCKET = "reeval-watcher-arrival";

    private final ReevaluationProperties properties;
    private final InterestIndex interestIndex;
    private final ReevaluationTrigger trigger;
    private final SearchTimeBusQueryPort busApi;
    private final BusApiQuotaPort quota;
    private final BusRealtimeCacheWritePort cacheWrite;
    private final LeaderLease leaderLease;
    private final JourneyReevalMetrics metrics;
    private final Clock clock;

    private final Map<String, Instant> lastFetched = new ConcurrentHashMap<>();
    private final Map<String, Instant> emptyUntil = new ConcurrentHashMap<>();
    /** 노선별 연달아 비거나 실패한 횟수. 한 번의 장애로 노선을 쉬게 하지 않으려는 것이다. */
    private final Map<String, Integer> emptyStreak = new ConcurrentHashMap<>();
    private final Map<String, InMemoryChangeDetector<InterestKey.Bus, BusMark>> detectors = new HashMap<>();
    private long seenEpoch = -1;

    public BusRouteWatcher(ReevaluationProperties properties, InterestIndex interestIndex,
                           ReevaluationTrigger trigger, SearchTimeBusQueryPort busApi, BusApiQuotaPort quota,
                           BusRealtimeCacheWritePort cacheWrite, LeaderLease leaderLease,
                           JourneyReevalMetrics metrics, Clock clock) {
        this.properties = properties;
        this.interestIndex = interestIndex;
        this.trigger = trigger;
        this.busApi = busApi;
        this.quota = quota;
        this.cacheWrite = cacheWrite;
        this.leaderLease = leaderLease;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${journey.navigation.reeval.bus-watcher-tick-ms:1000}")
    public void run() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("[BusRouteWatcher] tick 실패: {}", e.getMessage(), e);
        }
    }

    synchronized void tick() {
        if (!properties.isEnabled() || !properties.isBusWatcher() || !leaderLease.isLeader()) {
            return;
        }
        if (leaderLease.epoch() != seenEpoch) {
            detectors.clear();
            seenEpoch = leaderLease.epoch();
        }
        Instant now = Instant.now(clock);
        Map<String, Map<InterestKey.Bus, ReevaluationTier>> byRoute = interestIndex.busKeysByRoute();
        detectors.keySet().retainAll(byRoute.keySet());

        boolean degraded = false;
        long budgetEnds = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS
                .toNanos(properties.getBusTickBudgetMs());
        for (DueRoute due : dueRoutes(byRoute, now)) {
            if (!leaderLease.isLeader() || System.nanoTime() > budgetEnds) {
                // 남은 노선은 다음 tick 에. 공용 스케줄러 스레드를 오래 붙잡지 않는다.
                break;
            }
            if (!quota.tryAcquire(QUOTA_BUCKET, properties.getBusWatcherDailyLimit())) {
                degraded = true;
                break;
            }
            BusQueryResult<BusArrivalInfo> result =
                    busApi.getArrInfoByRouteAll(due.routeId(), properties.getBusCallTimeoutMs());
            if (result.outcome() == SearchTimeBusQueryPort.Outcome.BLOCKED
                    || result.outcome() == SearchTimeBusQueryPort.Outcome.LIMITED) {
                degraded = true;
                break;
            }
            metrics.recordBusWatcherCall();
            lastFetched.put(due.routeId(), now);
            if (!result.isOk() || result.data().isEmpty()) {
                // 실패와 빈 응답(서울 노선이 아니면 "결과 없음")을 같이 센다. 연달아 쌓일 때만 쉰다.
                int streak = emptyStreak.merge(due.routeId(), 1, Integer::sum);
                if (streak >= properties.getBusEmptyStreakToBackoff()) {
                    emptyUntil.put(due.routeId(),
                            now.plus(Duration.ofMinutes(properties.getBusEmptyRouteBackoffMinutes())));
                    emptyStreak.remove(due.routeId());
                }
                continue;
            }
            emptyStreak.remove(due.routeId());
            handle(due.routeId(), result.data(), byRoute.get(due.routeId()));
        }
        metrics.recordBusWatcherState(degraded, emptyUntil.values().stream().filter(now::isBefore).count());
    }

    /** 이번 tick 에 부를 노선. 주기가 됐고 쉬는 중이 아닌 노선을, 급한 등급·구독 수 순으로. */
    private List<DueRoute> dueRoutes(Map<String, Map<InterestKey.Bus, ReevaluationTier>> byRoute, Instant now) {
        List<DueRoute> due = new ArrayList<>();
        byRoute.forEach((routeId, keys) -> {
            Instant until = emptyUntil.get(routeId);
            if (until != null && now.isBefore(until)) {
                return;
            }
            ReevaluationTier tier = keys.values().stream()
                    .min(Comparator.comparingInt(Enum::ordinal))
                    .orElse(ReevaluationTier.RELAXED);
            Instant last = lastFetched.get(routeId);
            if (last != null && Duration.between(last, now).getSeconds() < properties.busCadenceSeconds(tier)) {
                return;
            }
            due.add(new DueRoute(routeId, tier, keys.size()));
        });
        due.sort(Comparator.comparingInt((DueRoute d) -> d.tier().ordinal())
                .thenComparing(Comparator.comparingInt(DueRoute::keyCount).reversed()));
        return due.size() > properties.getBusMaxRoutesPerTick()
                ? due.subList(0, Math.max(0, properties.getBusMaxRoutesPerTick()))
                : due;
    }

    private void handle(String routeId, List<BusArrivalInfo> items, Map<InterestKey.Bus, ReevaluationTier> watched) {
        Map<InterestKey.Bus, List<BusArrivalInfo>> grouped = new HashMap<>();
        for (BusArrivalInfo info : items) {
            if (info.stopId() == null || info.seq() == null) {
                continue;
            }
            grouped.computeIfAbsent(new InterestKey.Bus(info.stopId(), routeId, info.seq()),
                    k -> new ArrayList<>()).add(info);
        }
        if (!properties.isShadow()) {
            grouped.forEach((key, group) -> cacheWrite.putArrivals(key.stopId(), routeId, key.seq(), group));
        }

        Map<InterestKey.Bus, BusMark> observed = new HashMap<>();
        grouped.forEach((key, group) -> observed.put(key, BusMark.of(group.get(0))));
        InMemoryChangeDetector<InterestKey.Bus, BusMark> detector =
                detectors.computeIfAbsent(routeId, r -> new InMemoryChangeDetector<>());
        Set<InterestKey.Bus> changed = detector.changed(observed);
        metrics.recordCompared(SOURCE, observed.size(), changed.size());

        Set<InterestKey.Bus> interesting = new HashSet<>();
        for (InterestKey.Bus key : changed) {
            if (watched != null && watched.containsKey(key)) {
                interesting.add(key);
            }
        }
        if (interesting.isEmpty()) {
            detector.accept(observed, Set.of());
            return;
        }
        detector.accept(observed, deliver(interesting));
    }

    /** @return 반영하지 못해 기준선을 남길 키 */
    private Set<InterestKey.Bus> deliver(Set<InterestKey.Bus> keys) {
        Map<InterestKey.Bus, Set<Long>> journeysByKey = new HashMap<>();
        Map<ReevaluationTier, Set<Long>> byTier = new HashMap<>();
        for (InterestKey.Bus key : keys) {
            Set<Long> ids = new HashSet<>();
            interestIndex.journeysFor(List.of(key)).forEach((tier, journeys) -> {
                ids.addAll(journeys);
                byTier.computeIfAbsent(tier, t -> new HashSet<>()).addAll(journeys);
            });
            journeysByKey.put(key, ids);
        }
        if (!leaderLease.isLeader()) {
            return keys;
        }
        TriggerResult result;
        try {
            result = trigger.request(byTier);
        } catch (RuntimeException e) {
            log.warn("[BusRouteWatcher] 트리거 실패 — 다음 조회에 다시 보낸다: {}", e.getMessage());
            return keys;
        }
        Set<InterestKey.Bus> keep = new HashSet<>();
        journeysByKey.forEach((key, ids) -> {
            if (ids.stream().anyMatch(result.deferred()::contains)) {
                keep.add(key);
            }
        });
        return keep;
    }

    private record DueRoute(String routeId, ReevaluationTier tier, int keyCount) {
    }

    /**
     * 버스 정류장 한 칸의 지문.
     *
     * @param status 곧 도착·출발 대기·운행 종료·그 밖(운행 중)
     */
    record BusMark(String vehicle1, Integer section1, String status, String vehicle2) {

        static BusMark of(BusArrivalInfo info) {
            return new BusMark(blankToNull(info.vehicleId1()), info.sectionOrder1(),
                    status(info.arrivalMsg1()), blankToNull(info.vehicleId2()));
        }

        private static String status(String message) {
            if (message == null) {
                return "none";
            }
            if (message.contains("곧 도착")) {
                return "soon";
            }
            if (message.contains("출발대기")) {
                return "waiting";
            }
            if (message.contains("운행종료")) {
                return "ended";
            }
            return "running";
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() || "0".equals(value) ? null : value;
        }
    }
}
