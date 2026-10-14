package watoo.grd.nextroute.application.journey.reeval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.observability.JourneyReevalMetrics;
import watoo.grd.nextroute.application.subway.service.SubwayDirectionNormalizer;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository.InterestRow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 실시간 키 → 그 키를 지켜보는 여정. 변화 감지가 "누구를 깨울지" 묻는 곳이다.
 *
 * <p><b>메모리에 두고 주기적으로 다시 만든다.</b> 정류장·노선 ID 로 여정을 찾는 DB 인덱스를 두는
 * 대신, 진행 중 여정 전체를 SQL 한 번으로 훑어 메모리에서 거꾸로 찾는다. 감시 쪽이 매 스냅숏(15초)
 * 마다 묻는데 그때마다 DB 를 치지 않게 하려는 것이다.
 *
 * <p>재구성 사이(기본 20초)에 새로 시작한 여정은 색인에 없다 — 시작할 때 이미 다음 평가가 "지금"이라
 * 첫 평가는 늦지 않는다. 끝난 여정이 색인에 남아 있어도 트리거가 진행 중 행만 고쳐 무해하다.
 *
 * <p>등급은 조회할 때 현재 시각으로 다시 계산한다. 같은 여정도 시간이 지나면 급해진다.
 */
@Slf4j
@Component
public class InterestIndex {

    private final JourneySessionRepository sessionRepository;
    private final ReevaluationProperties properties;
    private final JourneyEvaluatorProperties evaluatorProperties;
    private final JourneyReevalMetrics metrics;
    private final Clock clock;

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public InterestIndex(JourneySessionRepository sessionRepository,
                         ReevaluationProperties properties,
                         JourneyEvaluatorProperties evaluatorProperties,
                         JourneyReevalMetrics metrics,
                         Clock clock) {
        this.sessionRepository = sessionRepository;
        this.properties = properties;
        this.evaluatorProperties = evaluatorProperties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * 키들을 지켜보는 여정을 등급별로 돌려준다. 색인이 낡았으면 먼저 다시 만든다.
     */
    public Map<ReevaluationTier, Set<Long>> journeysFor(Collection<? extends InterestKey> keys) {
        Snapshot current = fresh();
        Instant now = Instant.now(clock);
        Map<ReevaluationTier, Set<Long>> out = new EnumMap<>(ReevaluationTier.class);
        for (InterestKey key : keys) {
            for (Long journeyId : current.byKey().getOrDefault(key, Set.of())) {
                ReevaluationTier tier = tierOf(current.urgency().get(journeyId), now);
                out.computeIfAbsent(tier, t -> new HashSet<>()).add(journeyId);
            }
        }
        return out;
    }

    /**
     * 이 키를 지켜보는 구간이 쓰는 역·정류장 이름. 평가기는 이 이름으로 역별 캐시를 채우고 읽으므로, 캐시를
     * 지울 때도 이 이름을 써야 한다(원천 스냅숏의 역 이름과 다를 수 있다 — "서울" vs "서울역").
     */
    public Set<String> stopNamesFor(InterestKey key) {
        return fresh().stopNames().getOrDefault(key, Set.of());
    }

    /**
     * 버스 키를 노선별로 묶고, 키마다 지켜보는 여정 중 가장 급한 등급을 붙인다. 노선 감시자가 어느 노선을
     * 얼마나 자주 부를지 정하는 재료다.
     */
    public Map<String, Map<InterestKey.Bus, ReevaluationTier>> busKeysByRoute() {
        Snapshot current = fresh();
        Instant now = Instant.now(clock);
        Map<String, Map<InterestKey.Bus, ReevaluationTier>> out = new HashMap<>();
        current.byKey().forEach((key, journeys) -> {
            if (!(key instanceof InterestKey.Bus bus) || bus.seq() == null) {
                // 순번이 없으면 평가기도 도착을 묻지 않는다(정류장·노선·순번이 다 있어야 조회한다).
                return;
            }
            ReevaluationTier best = ReevaluationTier.RELAXED;
            for (Long journeyId : journeys) {
                ReevaluationTier tier = tierOf(current.urgency().get(journeyId), now);
                if (tier.ordinal() < best.ordinal()) {
                    best = tier;
                }
            }
            out.computeIfAbsent(bus.routeId(), r -> new HashMap<>()).put(bus, best);
        });
        return out;
    }

    /** 지금 이 키를 지켜보는 여정이 있는지. */
    public boolean watched(InterestKey key) {
        return fresh().byKey().containsKey(key);
    }

    /** 색인을 지금 다시 만든다. 시험과 운영 점검용. */
    public void rebuild() {
        long started = System.nanoTime();
        List<InterestRow> rows;
        try {
            rows = sessionRepository.findInterestRows();
        } catch (DataAccessException e) {
            // 옛 색인을 그대로 쓴다. 다음 조회에서 다시 시도한다.
            log.warn("[InterestIndex] 재구성 실패 — 직전 색인 유지: {}", e.getMessage());
            return;
        }
        Map<InterestKey, Set<Long>> byKey = new HashMap<>();
        Map<InterestKey, Set<String>> stopNames = new HashMap<>();
        Map<Long, Urgency> urgency = new HashMap<>();
        for (InterestRow row : rows) {
            InterestKey key = keyOf(row);
            if (key != null) {
                byKey.computeIfAbsent(key, k -> new HashSet<>()).add(row.getJourneyId());
                if (row.getStopName() != null) {
                    stopNames.computeIfAbsent(key, k -> new HashSet<>()).add(row.getStopName());
                }
            }
            urgency.putIfAbsent(row.getJourneyId(),
                    new Urgency(row.getNextReadyAt(), Boolean.TRUE.equals(row.getRisky())));
        }
        snapshot = new Snapshot(Map.copyOf(byKey), Map.copyOf(stopNames), Map.copyOf(urgency),
                Instant.now(clock));
        metrics.recordInterestRebuild(byKey, System.nanoTime() - started);
    }

    private Snapshot fresh() {
        Snapshot current = snapshot;
        Duration age = Duration.between(current.builtAt(), Instant.now(clock));
        if (current == Snapshot.EMPTY || age.getSeconds() >= properties.getInterestRebuildSeconds()) {
            rebuild();
            current = snapshot;
        }
        return current;
    }

    /**
     * 평가 간격 정책과 같은 기준({@code near-transfer}·{@code far-transfer}). 위험이면 긴급, 준비 시각을
     * 모르면 여유.
     */
    private ReevaluationTier tierOf(Urgency urgency, Instant now) {
        if (urgency == null) {
            return ReevaluationTier.RELAXED;
        }
        if (urgency.risky()) {
            return ReevaluationTier.URGENT;
        }
        if (urgency.nextReadyAt() == null) {
            return ReevaluationTier.RELAXED;
        }
        Duration until = Duration.between(now, urgency.nextReadyAt());
        if (until.compareTo(evaluatorProperties.nearTransfer()) <= 0) {
            return ReevaluationTier.URGENT;
        }
        if (until.compareTo(evaluatorProperties.farTransfer()) <= 0) {
            return ReevaluationTier.CAUTION;
        }
        return ReevaluationTier.RELAXED;
    }

    private static InterestKey keyOf(InterestRow row) {
        if (row.getRouteId() == null || row.getStopId() == null) {
            return null;
        }
        if ("WAIT_SUBWAY".equals(row.getLegType())) {
            String direction = SubwayDirectionNormalizer.fromWayCode(row.getRouteId(), row.getWayCode());
            // 방향을 모르면 평가기도 "모름"으로 끝낸다 — 지켜볼 키가 없다.
            return direction == null ? null
                    : new InterestKey.Subway(row.getRouteId(), row.getStopId(), direction);
        }
        if ("WAIT_BUS".equals(row.getLegType())) {
            return new InterestKey.Bus(row.getStopId(), row.getRouteId(), row.getBoardingSeq());
        }
        return null;
    }

    private record Urgency(Instant nextReadyAt, boolean risky) {
    }

    private record Snapshot(Map<InterestKey, Set<Long>> byKey, Map<InterestKey, Set<String>> stopNames,
                            Map<Long, Urgency> urgency, Instant builtAt) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of(), Instant.EPOCH);
    }
}
