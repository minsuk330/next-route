package watoo.grd.nextroute.application.route.routing;

import java.time.Instant;
import java.util.List;

/**
 * 성공 결과(canonical). legacy 응답은 API 경계의 LegacyRouteResponseMapper 가 여기서 파생한다.
 * 4개 버전(network/graph/mapping/geometry)을 모두 보존해 캐시 키·graph pinning·형상 무효화에 쓴다.
 *
 * @param graphVersion    OTP graph 버전. ODsay 는 {@link #ODSAY_NO_GRAPH} sentinel
 * @param mappingVersion  ID 매핑 artifact 버전(P2-D). 없으면 null
 * @param geometryVersion 형상 버전(P3). 없으면 null
 */
public record RoutingResult(
        RoutingProvider provider,
        String schemaVersion,
        String networkDataVersion,
        String graphVersion,
        String mappingVersion,
        String geometryVersion,
        Instant departureAt,
        List<String> warnings,
        List<RoutingItinerary> itineraries
) implements RoutingOutcome {

    /** ODsay 는 graphVersion 이 없음을 명시하는 provider 별 sentinel(OTP 버전과 혼용 금지). */
    public static final String ODSAY_NO_GRAPH = "ODSAY_NO_GRAPH";

    public RoutingResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        itineraries = itineraries == null ? List.of() : List.copyOf(itineraries);
    }

    @Override
    public boolean success() {
        return true;
    }
    /** itineraries 만 바꾼 복사본(나머지 필드 보존). */
    public RoutingResult withItineraries(java.util.List<RoutingItinerary> newItineraries) {
        return new RoutingResult(provider, schemaVersion, networkDataVersion, graphVersion, mappingVersion,
                geometryVersion, departureAt, warnings, newItineraries);
    }
}
