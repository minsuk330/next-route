package watoo.grd.nextroute.application.route.routing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * provider 무관 한 구간. 원천 ID 는 문자열 + feed namespace 로 보존한다.
 *
 * @param mode              provider 무관 수단. 미지원은 UNSUPPORTED(bus 변환 금지)
 * @param sourceDetailType  원천 세부타입(예: 고속철도) 보존용 문자열. 없으면 null
 * @param boardAt           정적 승차시각. 도보 leg 는 null
 * @param alightAt          정적 하차시각. 도보 leg 는 null
 * @param rideDurationSeconds 정적 승차 소요(초). 초 단위 보존(분 반올림은 legacy mapper 최종단계)
 * @param stops             지나는 정류장 occurrence(승·하차 포함)
 * @param walkSteps         도보 leg 의 turn. 대중교통 leg 는 빈 목록
 * @param missing           원천 부재 속성(가짜값 대신 사유로 노출)
 */
public record RoutingLeg(
        RoutingMode mode,
        String sourceDetailType,
        String feedId,
        String agencyId,
        String sourceRouteId,
        String sourceRouteName,
        String tripId,
        LocalDate serviceDate,
        Instant boardAt,
        Instant alightAt,
        int rideDurationSeconds,
        List<StopOccurrence> stops,
        List<RoutingWalkStep> walkSteps,
        LegGeometry geometry,
        Enrichment enrichment,
        Set<MissingAttribute> missing
) {
    public RoutingLeg {
        stops = stops == null ? List.of() : List.copyOf(stops);
        walkSteps = walkSteps == null ? List.of() : List.copyOf(walkSteps);
        missing = missing == null ? Set.of() : Set.copyOf(missing);
    }

    public boolean isTransit() {
        return mode == RoutingMode.BUS || mode == RoutingMode.SUBWAY || mode == RoutingMode.RAIL;
    }
}
