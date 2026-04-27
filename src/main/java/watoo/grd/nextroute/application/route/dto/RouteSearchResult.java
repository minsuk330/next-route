package watoo.grd.nextroute.application.route.dto;

import java.util.List;

public record RouteSearchResult(
        int searchType,
        int busCount,
        int subwayCount,
        int trainCount,
        int airCount,
        List<PathResult> paths
) {
}
