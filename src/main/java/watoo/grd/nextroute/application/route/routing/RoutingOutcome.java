package watoo.grd.nextroute.application.route.routing;

/**
 * 라우팅 호출 결과. 성공({@link RoutingResult})과 실패({@link RoutingFailure})를 sealed 로 구분해
 * 실패를 빈 목록으로 삼키지 않게 한다(RemainingRouteSearchService 가 실패를 empty 로 흡수하던 문제).
 */
public sealed interface RoutingOutcome permits RoutingResult, RoutingFailure {

    boolean success();
}
