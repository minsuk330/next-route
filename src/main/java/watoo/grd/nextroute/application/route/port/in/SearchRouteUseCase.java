package watoo.grd.nextroute.application.route.port.in;

import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;

public interface SearchRouteUseCase {
    RouteSearchResult search(RouteSearchRequest request);
}
