package watoo.grd.nextroute.application.route.routing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 지하철 대안을 더 찾을지, 찾는다면 어느 노선을 빼고 물을지 정한다(otp-kakao-parity). 순수 계산.
 *
 * <p>OTP 는 지배당한 경로(더 늦게 도착·비용 높음)를 탐색 단계에서 버린다 — 강남→홍대는 2호선 하나만 남고, 카카오맵이 보이는
 * 신분당선→3호선, 9호선→경의중앙선 같은 대안이 없다. 가장 좋은 지하철 경로가 탄 노선을 하나씩 빼고 지하철만 다시 물으면
 * 그 대안이 나온다(2026-11-16 운영 OTP: 강남→홍대 1→6개, 천호→홍대 1→5개, 호출 1~2회).
 */
public final class RailAlternatives {

    private RailAlternatives() {
    }

    /** 대중교통 구간이 전부 철도(지하철·철도)인 경로인가. 도보만 있는 경로는 아니다. */
    public static boolean isRailOnly(RoutingItinerary it) {
        boolean any = false;
        for (RoutingLeg leg : it.legs()) {
            if (leg.mode() == RoutingMode.WALK) {
                continue;
            }
            if (leg.mode() != RoutingMode.SUBWAY && leg.mode() != RoutingMode.RAIL) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * @param ranked    비용 순 대표 경로
     * @param threshold 철도만 타는 경로가 이 수 이상이면 더 묻지 않는다
     * @param maxLines  빼고 물을 노선 수 상한(= 추가 호출 수)
     * @return 빼고 물을 노선 이름들. empty = 더 묻지 않는다. 빈 목록이 든 Optional = 철도 경로가 하나도 없으니 철도만 한 번 묻는다
     */
    public static Optional<List<String>> linesToExclude(List<RoutingItinerary> ranked, int threshold, int maxLines) {
        List<RoutingItinerary> railOnly = ranked.stream().filter(RailAlternatives::isRailOnly).toList();
        if (railOnly.size() >= threshold || maxLines <= 0) {
            return Optional.empty();
        }
        if (railOnly.isEmpty()) {
            return Optional.of(List.of());
        }
        Set<String> lines = new LinkedHashSet<>();
        for (RoutingLeg leg : railOnly.get(0).legs()) {
            if (leg.mode() != RoutingMode.WALK && leg.sourceRouteName() != null) {
                lines.add(leg.sourceRouteName());
            }
        }
        return Optional.of(new ArrayList<>(lines).subList(0, Math.min(maxLines, lines.size())));
    }
}
