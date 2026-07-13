package watoo.grd.nextroute.application.route.port.out;

import watoo.grd.nextroute.application.route.dto.LaneGraphicResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;

import java.util.List;

public interface OdSayApiPort {

    /**
     * 대중교통 경로 검색.
     *
     * @param timeoutMs 이 호출에 허용된 시간 예산(ms). 재시도까지 포함해 이 안에서 끝나야 한다.
     *                  0 이하면 예산이 없다는 뜻이므로 호출자는 호출하지 않아야 한다.
     */
    RouteSearchResult searchPath(double sx, double sy, double ex, double ey, long timeoutMs);

    List<LaneGraphicResult> loadLane(String mapObj);
}
