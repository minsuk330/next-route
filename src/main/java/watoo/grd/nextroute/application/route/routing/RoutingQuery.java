package watoo.grd.nextroute.application.route.routing;

import java.time.Instant;
import java.util.Set;

/**
 * provider 무관 검색 질의. 좌표는 lon/lat 명시 필드로 둔다(startX/Y 혼동 방지).
 * departureAt 은 요청 경계에서 이미 확정된 값이어야 한다(주입 Clock, offset 필수).
 *
 * @param modes        허용 수단. 비면 전체
 * @param maxWalkMeters 접근 도보 상한(있으면). 캐시 키에 정렬 직렬화로 반영
 * @param provider     상위 서비스가 선택한 provider(어댑터는 이것만 호출)
 * @param graphVersion 고정할 graph 버전(있으면). ODsay 는 무시
 */
public record RoutingQuery(
        double originLon,
        double originLat,
        String originName,
        double destLon,
        double destLat,
        String destName,
        Instant departureAt,
        Set<RoutingMode> modes,
        Integer maxWalkMeters,
        boolean wheelchair,
        String schemaVersion,
        RoutingProvider provider,
        String graphVersion
) {
    public RoutingQuery {
        if (departureAt == null) {
            throw new IllegalArgumentException("departureAt must be resolved at request boundary");
        }
        if (provider == null) {
            throw new IllegalArgumentException("provider must be selected by upstream service");
        }
        modes = modes == null ? Set.of() : Set.copyOf(modes);
    }
}
