package watoo.grd.nextroute.application.subway.port.out;

import watoo.grd.nextroute.application.subway.dto.OtpRailRoute;
import watoo.grd.nextroute.application.subway.dto.RailPattern;

import java.time.Duration;
import java.util.List;

/**
 * OTP(GTFS) 지하철 노선·패턴 원천. 노선 선택·방향 보정은 부르는 쪽이 한다.
 *
 * <p>둘 다 실패하면 예외를 던진다. 빈 목록은 "그런 노선이 없다"는 뜻으로만 쓴다.
 *
 * <p>{@code timeout} 은 이 호출 하나의 응답 대기 한계다. 부르는 쪽이 남은 예산을 넘긴다(#291).
 */
public interface SubwayRoutePatternPort {

    List<OtpRailRoute> subwayRoutes(Duration timeout);

    List<RailPattern> patterns(String routeGtfsId, Duration timeout);
}
