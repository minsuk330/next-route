package watoo.grd.nextroute.application.route.routing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 엔진이 준 후보를 정적 일반화비용 순으로 줄 세우고, 같은 대중교통 흐름은 하나만 남긴다. 순수 계산.
 *
 * <p><b>왜 엔진 순서를 그대로 쓰지 않나</b>(2026-10-12 실측): OTP depart-after 기본 정렬은 도착시각이 먼저다.
 * 탐색 창 안의 출발편이 도착 순으로 늘어서므로, 배차가 잦고 노선이 많은 버스가 상위를 채우고
 * 같은 노선이 출발시각만 바꿔 반복된다(강남→홍대 상위 5개에 지하철 0개). 환승 수는 도착이 같을 때만 본다.
 * 일반화비용은 환승·도보·대기 벌점이 들어간 엔진 자신의 점수라, 이걸로 줄 세운다.
 *
 * <p>규칙
 * <ol>
 *   <li>지문 = 도보가 아닌 구간을 순서대로 (수단, feed, 노선 id). 노선 id 가 없으면 노선 이름.
 *       이름이 같아도 id 가 다르면 다른 경로다(1호선 일반·급행). 도보 구간 차이는 지문에 넣지 않는다.</li>
 *   <li>지문마다 대표 하나 = 가장 이른 도착 → 낮은 비용 → 엔진 순서. 다음 출발편은 대표가 될 수 없다.</li>
 *   <li>대표를 비용 오름차순(모르면 뒤) → 엔진 순서로 줄 세운다.</li>
 *   <li>대표가 상한보다 적으면 남은 후보를 엔진 순서로 채운다(노선이 하나뿐인 구간).</li>
 * </ol>
 *
 * <p>실시간 판단(놓침·지연)은 여기서 하지 않는다 — 정적 값만 본다.
 */
public final class ItineraryRanker {

    private ItineraryRanker() {
    }

    public static List<RoutingItinerary> rank(List<RoutingItinerary> engineOrder, int limit) {
        if (engineOrder == null || engineOrder.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>(engineOrder.size());
        for (int i = 0; i < engineOrder.size(); i++) {
            candidates.add(new Candidate(engineOrder.get(i), i));
        }

        Map<List<String>, Candidate> representatives = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            representatives.merge(fingerprint(c.itinerary()), c,
                    (kept, next) -> REPRESENTATIVE_ORDER.compare(kept, next) <= 0 ? kept : next);
        }

        List<Candidate> ranked = new ArrayList<>(representatives.values());
        ranked.sort(RANK_ORDER);

        List<RoutingItinerary> out = new ArrayList<>(Math.min(limit, candidates.size()));
        Set<Integer> chosen = new HashSet<>();
        for (Candidate c : ranked) {
            if (out.size() == limit) {
                return List.copyOf(out);
            }
            out.add(c.itinerary());
            chosen.add(c.engineIndex());
        }
        for (Candidate c : candidates) {
            if (out.size() == limit) {
                break;
            }
            if (!chosen.contains(c.engineIndex())) {
                out.add(c.itinerary());
            }
        }
        return List.copyOf(out);
    }

    private static List<String> fingerprint(RoutingItinerary itinerary) {
        List<String> key = new ArrayList<>();
        for (RoutingLeg leg : itinerary.legs()) {
            if (leg.mode() == RoutingMode.WALK) {
                continue;
            }
            String route = leg.sourceRouteId() != null ? leg.sourceRouteId() : leg.sourceRouteName();
            key.add(leg.mode() + "|" + leg.feedId() + "|" + route);
        }
        return key;
    }

    private record Candidate(RoutingItinerary itinerary, int engineIndex) {
        Instant arrival() {
            return itinerary.staticArrivalAt();
        }

        Integer cost() {
            return itinerary.generalizedCost();
        }
    }

    private static final Comparator<Candidate> BY_COST = Comparator.comparing(
            Candidate::cost, Comparator.nullsLast(Comparator.naturalOrder()));

    private static final Comparator<Candidate> REPRESENTATIVE_ORDER = Comparator
            .comparing(Candidate::arrival, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(BY_COST)
            .thenComparingInt(Candidate::engineIndex);

    private static final Comparator<Candidate> RANK_ORDER = BY_COST
            .thenComparingInt(Candidate::engineIndex);
}
