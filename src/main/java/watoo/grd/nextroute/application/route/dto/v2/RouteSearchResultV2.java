package watoo.grd.nextroute.application.route.dto.v2;

import java.time.Instant;
import java.util.List;

/**
 * capability(v2) 응답. legacy 와 달리 provider/버전·nullable 요금·coverage 를 정직하게 노출한다.
 * 구형 앱은 legacy 를 받고, capability 를 밝힌 앱만 이 응답을 받는다(진입점 배선은 후속 PR).
 */
public record RouteSearchResultV2(
        String provider,
        String schemaVersion,
        String graphVersion,
        Instant departureAt,
        List<PathResultV2> paths
) {
}
