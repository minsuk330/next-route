package watoo.grd.nextroute.application.journey.port.out;

import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.routing.RoutingCacheContext;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 남은 구간 경로 검색 결과의 캐시.
 *
 * <p><b>키에 여정도 사용자도 넣지 않는다.</b> "이 정류장에서 저 목적지로 가는 경로 구조"는 누가
 * 묻든 같은 답이라, 공유해야 유료 호출이 줄어든다. cooldown 이 요청 수를 줄이는 것과 다른 축이다 —
 * cooldown 은 같은 사람의 연타를 막고, 이 캐시는 <b>다른 사람의 같은 질문</b>을 막는다.
 *
 * <p><b>fail-open 이다.</b> 캐시가 죽으면 없는 것처럼 동작해 제안이 계속 나온다. cooldown 은 반대로
 * fail-closed 인데, 그쪽은 열리면 요금이 새고 이쪽은 닫히면 기능이 죽기 때문이다.
 */
public interface RemainingRouteCachePort {

    /** 실패하면 빈 결과. 예외를 던지지 않는다. */
    Optional<List<PathResult>> find(RoutingCacheContext ctx, String stopId, double destX, double destY);

    /** 실패해도 조용히 지나간다. 캐시에 못 넣는 것이 검색을 실패시키면 안 된다. */
    void put(RoutingCacheContext ctx, String stopId, double destX, double destY, List<PathResult> paths);

    /**
     * 캐시와 중복 억제(SingleFlight)가 <b>같은 키</b>를 써야 한다. 다르면 캐시는 맞히지 못하면서
     * 동시 요청만 합쳐지거나 그 반대가 된다.
     *
     * <p>좌표는 소수점 5자리로 고정해 붙인다 — 부동소수 표기가 환경에 따라 흔들리면 같은 지점이
     * 다른 키가 된다. 5자리면 약 1m 로, 정류장 하나를 가리키기에 충분하다.
     */
    static String key(RoutingCacheContext ctx, String stopId, double destX, double destY) {
        return ctx.keyPrefix() + ":" + stopId + ":" + String.format(Locale.ROOT, "%.5f", destX)
                + ":" + String.format(Locale.ROOT, "%.5f", destY);
    }
}
