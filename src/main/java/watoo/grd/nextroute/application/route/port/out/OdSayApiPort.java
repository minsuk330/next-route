package watoo.grd.nextroute.application.route.port.out;

import watoo.grd.nextroute.application.route.dto.RouteSearchResult;

public interface OdSayApiPort {
    RouteSearchResult searchPath(double sx, double sy, double ex, double ey);
}
