package watoo.grd.nextroute.application.subway.port.out;

import watoo.grd.nextroute.application.subway.dto.OtpRailRoute;
import watoo.grd.nextroute.application.subway.dto.RailPattern;

import java.util.List;

/**
 * OTP(GTFS) 지하철 노선·패턴 원천. 노선 선택·방향 보정은 부르는 쪽이 한다.
 *
 * <p>둘 다 실패하면 예외를 던진다. 빈 목록은 "그런 노선이 없다"는 뜻으로만 쓴다.
 */
public interface SubwayRoutePatternPort {

    List<OtpRailRoute> subwayRoutes();

    List<RailPattern> patterns(String routeGtfsId);
}
