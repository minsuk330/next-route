package watoo.grd.nextroute.application.journey.reeval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.observability.JourneyReevalMetrics;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalCode;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.port.out.SubwayStationArrivalCachePort;
import watoo.grd.nextroute.common.lock.LeaderLease;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 지하철 전 노선 스냅숏에서 바뀐 역을 찾아, 그 역을 지켜보는 여정의 다음 평가를 앞당긴다(#415-3).
 *
 * <p><b>추가 API 호출이 없다.</b> 수집기가 이미 15초마다 전 노선을 한 번에 받는다. 그 스냅숏을 비교만
 * 한다.
 *
 * <h2>지문</h2>
 * (노선, 역, 방향)마다 먼저 올 열차 두 대의 (열차 번호, 도착 코드, 지금 있는 역). 판정이 쓰는 값이
 * 바뀔 때만 달라지게 고른 것이다:
 * <ul>
 *   <li>열차가 바뀜(앞 열차가 떠나 다음 열차가 첫째가 됨) — 탈 수 있는 열차가 바뀐다</li>
 *   <li>도착 코드가 바뀜(진입·도착·출발) — 곧 떠나는지가 바뀐다</li>
 *   <li>지금 있는 역이 바뀜 — 남은 역 수가 줄어 예정 시각이 바뀐다</li>
 * </ul>
 * 남은 초({@code barvlDt})는 넣지 않는다. 0(= 미산출)이 잦아(#155) 넣으면 잡음으로 거의 매번 바뀐다.
 *
 * <h2>순서</h2>
 * 비교 → 지켜보는 키만 추림 → 그 역 캐시 삭제 → 트리거 → <b>반영된 키만</b> 기준선 갱신. 캐시를 먼저 지우는
 * 이유: 지우지 않으면 곧 다시 평가될 여정이 스냅숏보다 낡은 역별 캐시(10초)를 읽고 "변화 없음"이라
 * 한다. 반영 못 한 키의 기준선을 남겨 두는 이유: 다음 스냅숏에서 같은 변화가 다시 나와 이벤트를 잃지
 * 않는다.
 */
@Slf4j
@Component
public class SubwayChangeFeed {

    static final String SOURCE = "subway";

    private final ReevaluationProperties properties;
    private final InterestIndex interestIndex;
    private final ReevaluationTrigger trigger;
    private final SubwayStationArrivalCachePort stationCache;
    private final LeaderLease leaderLease;
    private final JourneyReevalMetrics metrics;

    private final InMemoryChangeDetector<InterestKey.Subway, List<TrainMark>> detector =
            new InMemoryChangeDetector<>();
    /** 지난 관측의 역 이름. 열차가 다 지나가 이번 스냅숏에서 사라진 역도 캐시를 지울 수 있게. */
    private final Map<InterestKey.Subway, String> stationNames = new HashMap<>();
    private long seenEpoch = -1;

    public SubwayChangeFeed(ReevaluationProperties properties, InterestIndex interestIndex,
                            ReevaluationTrigger trigger, SubwayStationArrivalCachePort stationCache,
                            LeaderLease leaderLease, JourneyReevalMetrics metrics) {
        this.properties = properties;
        this.interestIndex = interestIndex;
        this.trigger = trigger;
        this.stationCache = stationCache;
        this.leaderLease = leaderLease;
        this.metrics = metrics;
    }

    /** 수집기가 스냅숏을 저장한 직후 부른다. 실패는 삼켜 수집 성공에 영향을 주지 않는다. */
    public synchronized void onSnapshot(List<SubwayRealtimeTrain> trains) {
        if (!properties.isEnabled() || !properties.isSubwayTrigger() || trains == null) {
            return;
        }
        if (!leaderLease.isLeader()) {
            return;
        }
        if (leaderLease.epoch() != seenEpoch) {
            // 리더를 잃었다 되찾았다. 그사이 다른 리더가 본 변화를 모르므로 기준선을 다시 쌓는다.
            detector.reset();
            seenEpoch = leaderLease.epoch();
        }

        Map<InterestKey.Subway, List<TrainMark>> observed = fingerprints(trains);
        Set<InterestKey.Subway> changed = detector.changed(observed);
        metrics.recordCompared(SOURCE, observed.size(), changed.size());

        Set<InterestKey.Subway> watched = new HashSet<>();
        for (InterestKey.Subway key : changed) {
            if (interestIndex.watched(key)) {
                watched.add(key);
            }
        }
        if (watched.isEmpty()) {
            detector.accept(observed, Set.of());
            return;
        }

        Set<InterestKey.Subway> keep = deliver(watched);
        detector.accept(observed, keep);
    }

    /** @return 반영하지 못해 기준선을 남길 키 */
    private Set<InterestKey.Subway> deliver(Set<InterestKey.Subway> watched) {
        Map<InterestKey.Subway, Set<Long>> journeysByKey = new LinkedHashMap<>();
        Map<ReevaluationTier, Set<Long>> byTier = new HashMap<>();
        for (InterestKey.Subway key : watched) {
            Map<ReevaluationTier, Set<Long>> one = interestIndex.journeysFor(List.of(key));
            Set<Long> ids = new HashSet<>();
            one.forEach((tier, journeys) -> {
                ids.addAll(journeys);
                byTier.computeIfAbsent(tier, t -> new HashSet<>()).addAll(journeys);
            });
            journeysByKey.put(key, ids);
        }

        // 부작용 직전에 리더인지 다시 본다. 긴 정지로 마감이 지났으면 새 리더에게 맡긴다.
        if (!leaderLease.isLeader()) {
            return watched;
        }
        if (!properties.isShadow()) {
            // 평가기가 쓰는 구간의 역 이름과 원천 역 이름을 둘 다 지운다. 이름이 다르면 한쪽만 지워 낡은
            // 캐시가 남고, 다시 평가해도 "변화 없음"이라 이벤트를 잃는다.
            Set<String> names = new HashSet<>();
            for (InterestKey.Subway key : watched) {
                names.addAll(interestIndex.stopNamesFor(key));
                String sourceName = stationNames.get(key);
                if (sourceName != null) {
                    names.add(sourceName);
                }
            }
            names.forEach(stationCache::evict);
        }

        TriggerResult result;
        try {
            result = trigger.request(byTier);
        } catch (RuntimeException e) {
            log.warn("[SubwayChangeFeed] 트리거 실패 — 다음 스냅숏에 다시 보낸다: {}", e.getMessage());
            return watched;
        }
        Set<InterestKey.Subway> keep = new HashSet<>();
        journeysByKey.forEach((key, ids) -> {
            for (Long id : ids) {
                if (result.deferred().contains(id)) {
                    keep.add(key);
                    return;
                }
            }
        });
        return keep;
    }

    /** (노선, 역, 방향)별로 먼저 올 열차 두 대를 지문으로 만든다. 떠난 열차는 뺀다. */
    Map<InterestKey.Subway, List<TrainMark>> fingerprints(List<SubwayRealtimeTrain> trains) {
        Map<InterestKey.Subway, List<SubwayRealtimeTrain>> grouped = new HashMap<>();
        for (SubwayRealtimeTrain train : trains) {
            if (train.getLineId() == null || train.getStationId() == null || train.getDirection() == null) {
                continue;
            }
            if (SubwayArrivalCode.from(train.getArrivalCode()) == SubwayArrivalCode.DEPARTED) {
                continue;
            }
            InterestKey.Subway key = new InterestKey.Subway(
                    train.getLineId(), train.getStationId(), train.getDirection());
            grouped.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(train);
            if (train.getStationName() != null) {
                stationNames.put(key, train.getStationName());
            }
        }
        Map<InterestKey.Subway, List<TrainMark>> out = new HashMap<>();
        grouped.forEach((key, list) -> out.put(key, list.stream()
                .sorted(Comparator.comparing(SubwayRealtimeTrain::getArrivalSeconds,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(train -> new TrainMark(train.getTrainNo(), train.getArrivalCode(),
                        train.getCurrentStationName()))
                .distinct()
                .limit(2)
                .toList()));
        return out;
    }

    /** 지문의 열차 한 대. */
    record TrainMark(String trainNo, String arrivalCode, String currentStation) {
    }
}
