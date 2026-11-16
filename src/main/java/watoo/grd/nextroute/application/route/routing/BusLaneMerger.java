package watoo.grd.nextroute.application.route.routing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 대표 경로의 버스 구간마다 "같은 정류장에서 타서 같은 정류장에 내리는 다른 노선"을 확정한다(otp-bus-lane-merge). 순수 계산.
 *
 * <p>네이버·카카오는 이런 노선을 한 경로로 묶어 보여 준다(카카오 강남→홍대: 당산역13번출구 5714, 603, 761). 재료는 둘이다.
 * <ol>
 *   <li><b>A</b> — 같은 지문({@link ItineraryRanker#fingerprint(RoutingItinerary, boolean)}, 버스=승하차 정류장)으로 묶인
 *       다른 후보가 그 구간에서 탄 노선. 추가 비용 0.</li>
 *   <li><b>B</b> — 대표 구간의 OTP {@code nextLegs}(어댑터가 {@link RoutingLeg#alternatives()} 에 원재료로 실음). OTP 후보에
 *       안 나온 노선까지 채운다.</li>
 * </ol>
 *
 * <p><b>지나는 정류장이 대표와 완전히 같은 노선만 묶는다</b>(2026-11-16 사용자 결정). 운영 OTP 실측(묶인 노선 125개): 정류장이
 * 같은 92개는 대표와 탑승시간 차가 최대 2.2분·선 이탈 최대 44m, 정류장이 다른 33개는 최대 9.3분·1,010m 였다. 정류장이
 * 같으면 어느 노선을 타도 표시 시간·환승 대기·정거장 띠·지도 선이 맞는다.
 *
 * <p>거르기(2026-11-16 운영 OTP 실측 노이즈)
 * <ul>
 *   <li>승차 ~ 하차 정류장 목록이 대표와 다른 편(모르는 편 포함)</li>
 *   <li>대표 노선 자신, 이름 없는 편, 이름에 {@code (예약)} 이 든 노선({@code 5000(예약)}, {@code M5443(예약)}) — 예약제 버스</li>
 *   <li>하차 정류장이 대표와 다른 편(값을 알 때만)</li>
 *   <li>탑승시간 &gt; 대표 탑승시간 × factor + slack(수원 2-1 같은 우회 노선)</li>
 * </ul>
 * 같은 이름은 가장 이른 승차 한 편만 남기고, 자주 오는 순(받은 편 수) → 승차 이른 순 → 이름으로 줄 세워 {@code maxLanes - 1} 개까지 싣는다(대표 포함
 * lane 상한 = maxLanes). 버스가 아닌 구간과 정류장 id 를 모르는 버스 구간은 대체 노선을 비운다(어댑터 원재료가 lane 으로
 * 새지 않게).
 */
public final class BusLaneMerger {

    static final String RESERVATION_MARK = "(예약)";

    private BusLaneMerger() {
    }

    /**
     * @param maxLanes         대표 포함 lane 상한
     * @param rideFactor       탑승시간 배수 상한
     * @param rideSlackSeconds 탑승시간 여유(초)
     */
    public record Rules(int maxLanes, double rideFactor, int rideSlackSeconds) {
    }

    /**
     * @param representatives {@link ItineraryRanker#rank(List, int, boolean)} 가 고른 대표(버스=정류장 지문)
     * @param pool            대표를 고른 전체 후보(A 재료)
     */
    public static List<RoutingItinerary> merge(List<RoutingItinerary> representatives,
                                               List<RoutingItinerary> pool, Rules rules) {
        if (representatives == null || representatives.isEmpty()) {
            return List.of();
        }
        Map<List<String>, List<RoutingItinerary>> groups = new LinkedHashMap<>();
        for (RoutingItinerary it : pool == null ? List.<RoutingItinerary>of() : pool) {
            groups.computeIfAbsent(ItineraryRanker.fingerprint(it, true), k -> new ArrayList<>()).add(it);
        }
        List<RoutingItinerary> out = new ArrayList<>(representatives.size());
        for (RoutingItinerary rep : representatives) {
            List<RoutingItinerary> members = groups.getOrDefault(ItineraryRanker.fingerprint(rep, true), List.of());
            out.add(mergeOne(rep, members, rules));
        }
        return out;
    }

    /** 대체 노선을 모두 비운 복사본(버스 합치기를 끈 경우). */
    public static List<RoutingItinerary> clear(List<RoutingItinerary> itineraries) {
        if (itineraries == null) {
            return List.of();
        }
        return itineraries.stream()
                .map(it -> it.legs().stream().anyMatch(l -> !l.alternatives().isEmpty())
                        ? it.withLegs(it.legs().stream().map(l -> l.withAlternatives(List.of())).toList())
                        : it)
                .toList();
    }

    private static RoutingItinerary mergeOne(RoutingItinerary rep, List<RoutingItinerary> members, Rules rules) {
        List<RoutingLeg> legs = new ArrayList<>(rep.legs().size());
        int transitIndex = -1;
        for (RoutingLeg leg : rep.legs()) {
            if (leg.mode() == RoutingMode.WALK) {
                legs.add(leg.alternatives().isEmpty() ? leg : leg.withAlternatives(List.of()));
                continue;
            }
            transitIndex++;
            if (leg.mode() != RoutingMode.BUS || leg.boardStopId() == null || leg.alightStopId() == null) {
                legs.add(leg.alternatives().isEmpty() ? leg : leg.withAlternatives(List.of()));
                continue;
            }
            List<AlternativeRoute> candidates = new ArrayList<>(leg.alternatives());
            for (RoutingItinerary member : members) {
                if (member == rep) {
                    continue;
                }
                RoutingLeg same = transitLeg(member, transitIndex);
                if (same != null && same.mode() == RoutingMode.BUS) {
                    candidates.add(new AlternativeRoute(same.sourceRouteId(), same.sourceRouteName(),
                            same.sourceRouteColor(), same.alightStopId(), same.boardAt(), same.alightAt(),
                            stopIds(same)));
                }
            }
            legs.add(leg.withAlternatives(select(leg, candidates, rules)));
        }
        return rep.withLegs(legs);
    }

    static List<AlternativeRoute> select(RoutingLeg leg, List<AlternativeRoute> candidates, Rules rules) {
        int slots = Math.max(0, rules.maxLanes() - 1);
        if (slots == 0 || candidates.isEmpty()) {
            return List.of();
        }
        String repName = leg.sourceRouteName() != null ? leg.sourceRouteName() : leg.sourceRouteId();
        long maxRide = rideCap(leg, rules);
        List<String> repStops = stopIds(leg);
        Map<String, AlternativeRoute> byName = new LinkedHashMap<>();
        Map<String, Integer> trips = new java.util.HashMap<>();
        for (AlternativeRoute c : candidates) {
            String name = c.label();
            if (name == null || name.equals(repName) || name.contains(RESERVATION_MARK)) {
                continue;
            }
            if (c.alightStopId() != null && !c.alightStopId().equals(leg.alightStopId())) {
                continue;
            }
            // 승차부터 하차까지 서는 정류장이 대표와 완전히 같아야 묶는다. 모르면(빈 목록) 묶지 않는다.
            if (!c.stopIds().equals(repStops)) {
                continue;
            }
            Long ride = c.rideSeconds();
            if (maxRide >= 0 && ride != null && ride > maxRide) {
                continue;
            }
            byName.merge(name, c, (a, b) -> EARLIEST.compare(a, b) <= 0 ? a : b);
            trips.merge(name, 1, Integer::sum);
        }
        // 자주 오는 노선 먼저(받은 편 수), 같으면 승차 이른 순 → 이름. 상한에 걸릴 때 하루 몇 번 안 오는 노선이 자주 오는
        // 노선을 밀어내지 않는다(2026-11-16 사당역(중)→과천: 11-5·11-2 각 8편, 777·103 각 3편, 7007-1 2편 — 카카오 순서와 같다).
        return byName.values().stream()
                .sorted(Comparator.comparing((AlternativeRoute r) -> -trips.get(r.label()))
                        .thenComparing(EARLIEST).thenComparing(AlternativeRoute::label))
                .limit(slots)
                .toList();
    }

    /** 구간이 서는 정류장 원천 id(승차 ~ 하차). id 가 하나라도 없으면 빈 목록. */
    static List<String> stopIds(RoutingLeg leg) {
        List<String> ids = new ArrayList<>(leg.stops().size());
        for (StopOccurrence stop : leg.stops()) {
            if (stop.sourceStopId() == null) {
                return List.of();
            }
            ids.add(stop.sourceStopId());
        }
        return ids;
    }

    /** 대표 탑승시간 기준 상한(초). 대표 탑승시간을 모르면 -1(거르지 않음). */
    private static long rideCap(RoutingLeg leg, Rules rules) {
        long ride = leg.rideDurationSeconds();
        if (ride <= 0 && leg.boardAt() != null && leg.alightAt() != null) {
            ride = java.time.Duration.between(leg.boardAt(), leg.alightAt()).getSeconds();
        }
        if (ride <= 0) {
            return -1;
        }
        return Math.round(ride * rules.rideFactor()) + rules.rideSlackSeconds();
    }

    private static RoutingLeg transitLeg(RoutingItinerary it, int index) {
        int i = -1;
        for (RoutingLeg leg : it.legs()) {
            if (leg.mode() == RoutingMode.WALK) {
                continue;
            }
            if (++i == index) {
                return leg;
            }
        }
        return null;
    }

    private static final Comparator<AlternativeRoute> EARLIEST = Comparator.comparing(
            AlternativeRoute::boardAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()));
}
