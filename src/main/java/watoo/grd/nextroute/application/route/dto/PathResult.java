package watoo.grd.nextroute.application.route.dto;

import java.util.List;

public record PathResult(
        int pathType,
        PathInfo info,
        List<SubPathResult> subPaths
) {
}
