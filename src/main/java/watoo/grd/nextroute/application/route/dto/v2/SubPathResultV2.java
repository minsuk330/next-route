package watoo.grd.nextroute.application.route.dto.v2;

import java.util.List;

public record SubPathResultV2(
        String mode,
        String sourceDetailType,
        String sourceRouteId,
        String sourceRouteName,
        int rideDurationSeconds,
        String realtimeCoverage,
        String actualSource,
        String geometryQuality,
        List<String> missing
) {
}
