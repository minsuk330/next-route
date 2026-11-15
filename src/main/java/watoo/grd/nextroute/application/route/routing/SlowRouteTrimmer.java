package watoo.grd.nextroute.application.route.routing;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.Collections;

/**
 * 가장 빠른 경로보다 한참 느린 경로를 줄인다(otp-kakao-parity). 순수 계산.
 *
 * <p>추가 검색(버스만 등)은 지배당한 대안을 살려 내지만, 최단의 두 배가 넘는 다환승 버스 경로까지 줄줄이 붙는다
 * (2026-11-16 운영: 합정→잠실 최단 41분에 91~102분 8개, 목동→건대 52분에 100~110분 7개). 카카오맵 상위 목록에는 이런 경로가
 * 한두 개뿐이다. 그래서 최단 소요 × factor 를 넘는 경로는 가장 빠른 keep 개만 남긴다 — 다른 수단 대안은 보이되 목록을 채우지
 * 않는다. 남은 경로의 순서는 입력 순서(비용 순) 그대로다.
 */
public final class SlowRouteTrimmer {

    private SlowRouteTrimmer() {
    }

    /**
     * @param factor 최단 소요 대비 배수. 0 이하면 줄이지 않는다
     * @param keep   배수를 넘는 경로 중 남길 개수(빠른 순)
     */
    public static List<RoutingItinerary> trim(List<RoutingItinerary> ranked, double factor, int keep) {
        if (ranked == null || ranked.size() <= 1 || factor <= 0) {
            return ranked == null ? List.of() : ranked;
        }
        // 기준은 대중교통 경로의 최단이다 — 걸어서 12분인 거리에서 도보 경로를 기준으로 삼으면 버스 경로가 다 잘린다.
        int best = ranked.stream().filter(SlowRouteTrimmer::hasTransit)
                .mapToInt(SlowRouteTrimmer::totalSeconds).filter(s -> s > 0).min().orElse(0);
        if (best <= 0) {
            return ranked;
        }
        double cutoff = best * factor;
        Set<RoutingItinerary> keptSlow = Collections.newSetFromMap(new IdentityHashMap<>());
        ranked.stream()
                .filter(it -> totalSeconds(it) > cutoff)
                .sorted(Comparator.comparingInt(SlowRouteTrimmer::totalSeconds))
                .limit(Math.max(keep, 0))
                .forEach(keptSlow::add);
        return ranked.stream()
                .filter(it -> totalSeconds(it) <= cutoff || keptSlow.contains(it))
                .toList();
    }

    private static boolean hasTransit(RoutingItinerary it) {
        return it.legs().stream().anyMatch(leg -> leg.mode() != RoutingMode.WALK);
    }

    /** 응답 {@code totalTime} 과 같은 합(이동 + 대기 + 도보). */
    static int totalSeconds(RoutingItinerary it) {
        return it.moveSeconds() + it.waitSeconds() + it.walkSeconds();
    }
}
