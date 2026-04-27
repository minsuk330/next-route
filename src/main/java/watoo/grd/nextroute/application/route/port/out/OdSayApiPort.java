package watoo.grd.nextroute.application.route.port.out;

import watoo.grd.nextroute.application.route.dto.LaneGraphicResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;

import java.util.List;

public interface OdSayApiPort {
    RouteSearchResult searchPath(double sx, double sy, double ex, double ey);
    List<LaneGraphicResult> loadLane(String mapObj);
}
