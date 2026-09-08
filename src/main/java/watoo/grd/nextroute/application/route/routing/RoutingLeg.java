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
 * @param boardAt           구간 시작 시각(대중교통=정적 승차, 도보=이동 시작). provider 가 절대시각을 주면
 *                          채우고(OTP planConnection), 주지 않으면 null(ODsay 는 상대 소요시간만 주므로 null)
 * @param alightAt          구간 끝 시각(대중교통=정적 하차, 도보=이동 끝). boardAt 과 같은 규칙(provider 의존)
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

    /** enrichment 만 바꾼 복사본(나머지 필드 보존). 수동 전체 재생성 시 신규 필드 유실을 막는다. */
    public RoutingLeg withEnrichment(Enrichment newEnrichment) {
        return new RoutingLeg(mode, sourceDetailType, feedId, agencyId, sourceRouteId, sourceRouteName,
                tripId, serviceDate, boardAt, alightAt, rideDurationSeconds, stops, walkSteps, geometry,
                newEnrichment, missing);
    }
}
