package watoo.grd.nextroute.application.route.dto.v2;

import java.util.List;

public record PathResultV2(
        int totalTimeSeconds,
        int moveSeconds,
        int waitSeconds,
        int walkSeconds,
        int transferCount,
        FareV2 fare,
        boolean navigationSupported,
        List<String> navigationBlockedReasons,
        List<SubPathResultV2> subPaths
) {
}
