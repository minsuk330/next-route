package watoo.grd.nextroute.application.route.routing;

/**
 * 공통 라우팅 경계. ODsay·OTP adapter 가 각각 구현하고, provider 선택은 상위 서비스가 한다.
 * 최초 검색과 남은 경로 재탐색이 같은 port 를 쓴다.
 */
public interface RoutingPort {

    /** 선택된 provider 만 호출한다. 실패는 {@link RoutingFailure} 로 반환(예외/빈목록으로 삼키지 않음). */
    RoutingOutcome search(RoutingQuery query, RoutingDeadline deadline);

    /** 이 어댑터가 담당하는 provider. selector 가 라우팅한다. */
    RoutingProvider provider();
}
