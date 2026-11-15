package watoo.grd.nextroute.application.route.routing;

import watoo.grd.nextroute.application.subway.dto.TrainKind;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 엔진이 준 후보를 정적 일반화비용 순으로 줄 세우고, 같은 경로는 하나만 남긴다. 순수 계산.
 *
 * <p><b>왜 엔진 순서를 그대로 쓰지 않나</b>(2026-10-12 실측): OTP depart-after 기본 정렬은 도착시각이 먼저다.
 * 탐색 창 안의 출발편이 도착 순으로 늘어서므로, 배차가 잦고 노선이 많은 버스가 상위를 채우고
 * 같은 노선이 출발시각만 바꿔 반복된다(강남→홍대 상위 5개에 지하철 0개). 환승 수는 도착이 같을 때만 본다.
 * 일반화비용은 환승·도보·대기 벌점이 들어간 엔진 자신의 점수라, 이걸로 줄 세운다.
 *
 * <p>규칙
 * <ol>
 *   <li>지문 = 도보가 아닌 구간을 순서대로 (수단, 노선 이름[, 열차 종류]). 이름이 없으면 노선 id.
 *       응답에는 노선 이름만 나가므로 이름이 같으면 사용자에겐 같은 경로다 — 1호선 계통(연천-인천·광운대-신창)
 *       처럼 id 만 다른 것은 하나로 본다. <b>열차 종류가 일반이 아니면(급행·ITX 등) 지문에 덧붙인다</b>(subway-express
 *       S2, D2) — 응답이 {@code trainKind} 로 급행을 보여 주므로 같은 노선의 급행·일반은 다른 경로다. 종류 null 과
 *       NORMAL 은 같은 지문이다. 도보 구간 차이는 지문에 넣지 않는다.
 *       <b>버스 합치기를 켜면 버스 구간은 노선 대신 (승차 정류장, 하차 정류장)</b>(otp-bus-lane-merge) — 같은 정류장에서
 *       타서 같은 정류장에 내리는 다른 버스는 사용자에겐 같은 길이다(네이버·카카오가 한 경로로 묶는다). 묶인 노선은
 *       {@link BusLaneMerger} 가 대표 경로의 lane 으로 싣는다. 정류장 id 를 모르면 노선 이름으로 돌아간다. 정류장만 다른
 *       같은 노선 흐름(740 강남역9번출구·12번출구)은 줄 세운 뒤 위 대표 하나만 남긴다.</li>
 *   <li>지문마다 대표 하나 = 가장 이른 도착 → 낮은 비용 → 엔진 순서. 다음 출발편은 대표가 될 수 없다.</li>
 *   <li>대표를 비용 오름차순(모르면 뒤) → 엔진 순서로 줄 세운다. 대표만 내보낸다 — 고유 경로가 상한보다
 *       적어도 같은 경로의 다음 편으로 채우지 않는다(2026-10-12 운영: 부평→서울 1호선이 출발시각만 달리 3번).</li>
 * </ol>
 *
 * <p>실시간 판단(놓침·지연)은 여기서 하지 않는다 — 정적 값만 본다.
 */
public final class ItineraryRanker {

    private ItineraryRanker() {
    }

    /** 노선 이름 지문(버스 합치기 끔)으로 줄 세운다. */
    public static List<RoutingItinerary> rank(List<RoutingItinerary> engineOrder, int limit) {
        return rank(engineOrder, limit, false);
    }

    /**
     * @param busByStops 버스 구간 지문을 승하차 정류장으로(버스 합치기). false 면 노선 이름
     */
    public static List<RoutingItinerary> rank(List<RoutingItinerary> engineOrder, int limit, boolean busByStops) {
        if (engineOrder == null || engineOrder.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>(engineOrder.size());
        for (int i = 0; i < engineOrder.size(); i++) {
            candidates.add(new Candidate(engineOrder.get(i), i));
        }

        Map<List<String>, Candidate> representatives = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            representatives.merge(fingerprint(c.itinerary(), busByStops), c,
                    (kept, next) -> REPRESENTATIVE_ORDER.compare(kept, next) <= 0 ? kept : next);
        }

        List<Candidate> ranked = new ArrayList<>(representatives.values());
        ranked.sort(RANK_ORDER);
        if (busByStops) {
            // 정류장으로 묶으면 같은 노선이 정류장만 달리 두 번 나온다(2026-11-16 실측 강남→홍대: 740 강남역9번출구·
            // 강남역12번출구). 보이는 노선 흐름이 위 대표와 같으면 뺀다 — 노선 이름 지문 시절과 같은 규칙.
            java.util.Set<List<String>> seenNames = new java.util.HashSet<>();
            ranked.removeIf(c -> !seenNames.add(fingerprint(c.itinerary(), false)));
        }

        return ranked.stream()
                .limit(limit)
                .map(Candidate::itinerary)
                .toList();
    }

    /** 같은 경로를 가르는 지문(규칙은 클래스 주석). 일반 대안 되찾기({@code RouteSearchService})도 같은 규칙을 쓴다. */
    public static List<String> fingerprint(RoutingItinerary itinerary) {
        return fingerprint(itinerary, false);
    }

    /** @param busByStops 버스 구간을 승하차 정류장으로 가른다(버스 합치기) */
    public static List<String> fingerprint(RoutingItinerary itinerary, boolean busByStops) {
        List<String> key = new ArrayList<>();
        for (RoutingLeg leg : itinerary.legs()) {
            if (leg.mode() == RoutingMode.WALK) {
                continue;
            }
            if (busByStops && leg.mode() == RoutingMode.BUS
                    && leg.boardStopId() != null && leg.alightStopId() != null) {
                key.add(RoutingMode.BUS + "|" + leg.boardStopId() + ">" + leg.alightStopId());
                continue;
            }
            String route = leg.sourceRouteName() != null ? leg.sourceRouteName() : leg.sourceRouteId();
            TrainKind kind = leg.trainKind();
            key.add(kind == null || kind == TrainKind.NORMAL
                    ? leg.mode() + "|" + route
                    : leg.mode() + "|" + route + "|" + kind);
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
