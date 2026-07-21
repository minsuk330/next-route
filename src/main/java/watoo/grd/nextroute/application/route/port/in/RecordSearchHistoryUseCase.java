package watoo.grd.nextroute.application.route.port.in;

import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;

/**
 * 로그인 사용자의 경로 검색 이력을 기록한다. 컨트롤러가 검색 성공 후 호출한다.
 * 저장은 비동기이므로 이 호출은 응답 경로를 지연시키지 않는다(즉시 반환).
 */
public interface RecordSearchHistoryUseCase {

    void record(Long userId, RouteSearchRequest request, RouteSearchResult result);
}
