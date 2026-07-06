package watoo.grd.nextroute.application.route.port.in;

import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;

public interface SearchRouteUseCase {
    RouteSearchResult search(RouteSearchRequest request);

    /**
     * 실시간 환승 도착예측 보강(버스 API fan-out)을 생략한 검색.
     * baseline(등록 시 STATIC 추정)용 — 실시간 대기는 어차피 버려지므로 fan-out 낭비를 막는다.
     * 폴리라인·도보 실측 보강은 유지(정적 소요 계산에 필요).
     */
    RouteSearchResult searchWithoutRealtime(RouteSearchRequest request);
}
