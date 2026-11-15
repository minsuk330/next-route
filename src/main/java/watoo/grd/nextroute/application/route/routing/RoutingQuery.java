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
 * @param excludedRouteIds 탐색에서 뺄 원천 route id(급행 끄기·일반 대안 되찾기, subway-express S2). 비면 요청으로는 거르지 않는다
 *                         (공항철도 직통은 OTP 어댑터가 언제나 더해 뺀다, #495).
 *                     OTP 만 쓴다(ODsay 는 무시)
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
        String graphVersion,
        Set<String> excludedRouteIds,
        boolean alternativeLegs
) {
    /** 대체 버스 노선을 묻지 않는 호출부용. false 가 곧 옛 동작이다. */
    public RoutingQuery(double originLon, double originLat, String originName, double destLon, double destLat,
                        String destName, Instant departureAt, Set<RoutingMode> modes, Integer maxWalkMeters,
                        boolean wheelchair, String schemaVersion, RoutingProvider provider, String graphVersion,
                        Set<String> excludedRouteIds) {
        this(originLon, originLat, originName, destLon, destLat, destName, departureAt, modes, maxWalkMeters,
                wheelchair, schemaVersion, provider, graphVersion, excludedRouteIds, false);
    }

    /** 제외 노선 없는 호출부용. 빈 집합이 곧 옛 동작이다. */
    public RoutingQuery(double originLon, double originLat, String originName, double destLon, double destLat,
                        String destName, Instant departureAt, Set<RoutingMode> modes, Integer maxWalkMeters,
                        boolean wheelchair, String schemaVersion, RoutingProvider provider, String graphVersion) {
        this(originLon, originLat, originName, destLon, destLat, destName, departureAt, modes, maxWalkMeters,
                wheelchair, schemaVersion, provider, graphVersion, Set.of());
    }

    public RoutingQuery {
        if (departureAt == null) {
            throw new IllegalArgumentException("departureAt must be resolved at request boundary");
        }
        if (provider == null) {
            throw new IllegalArgumentException("provider must be selected by upstream service");
        }
        modes = modes == null ? Set.of() : Set.copyOf(modes);
        excludedRouteIds = excludedRouteIds == null ? Set.of() : Set.copyOf(excludedRouteIds);
    }

    /** 허용 수단만 바꾼 복사본(나머지 필드 보존 — 제외 노선도 그대로 간다). */
    public RoutingQuery withModes(Set<RoutingMode> newModes) {
        return new RoutingQuery(originLon, originLat, originName, destLon, destLat, destName, departureAt,
                newModes, maxWalkMeters, wheelchair, schemaVersion, provider, graphVersion, excludedRouteIds,
                alternativeLegs);
    }

    /** 제외 노선만 바꾼 복사본(나머지 필드 보존). */
    public RoutingQuery withExcludedRouteIds(Set<String> newExcludedRouteIds) {
        return new RoutingQuery(originLon, originLat, originName, destLon, destLat, destName, departureAt,
                modes, maxWalkMeters, wheelchair, schemaVersion, provider, graphVersion, newExcludedRouteIds,
                alternativeLegs);
    }

    /**
     * 버스 구간마다 같은 승하차 정류장의 다른 노선(OTP {@code nextLegs})도 함께 묻는 복사본(otp-bus-lane-merge).
     * 검색 화면용 질의만 켠다 — 재탐색은 노선을 묶어 보여 주지 않으니 비용만 든다.
     */
    public RoutingQuery withAlternativeLegs() {
        return new RoutingQuery(originLon, originLat, originName, destLon, destLat, destName, departureAt,
                modes, maxWalkMeters, wheelchair, schemaVersion, provider, graphVersion, excludedRouteIds, true);
    }
}
