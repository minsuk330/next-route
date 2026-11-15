package watoo.grd.nextroute.application.route.routing;

import watoo.grd.nextroute.application.subway.dto.TrainKind;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * provider 무관 한 구간. 원천 ID 는 문자열 + feed namespace 로 보존한다.
 *
 * @param mode              provider 무관 수단. 미지원은 UNSUPPORTED(bus 변환 금지)
 * @param sourceDetailType  원천 세부타입(예: 고속철도) 보존용 문자열. 없으면 null
 * @param sourceRouteDirection 원천이 말한 <b>방향 라벨</b>(상행·하행·내선·외선). 값을 해석하지 않고
 *                          원문 그대로 보존한다 — 숫자 코드로 바꾸는 규칙은 원천마다 다르고
 *                          (ODsay wayCode 와 서울 updnLine 의 대응이 다르다) 그 변환은 legacy
 *                          경계가 한 자리에서 한다. 못 읽었으면 null
 * @param boardAt           구간 시작 시각(대중교통=정적 승차, 도보=이동 시작). provider 가 절대시각을 주면
 *                          채우고(OTP planConnection), 주지 않으면 null(ODsay 는 상대 소요시간만 주므로 null)
 * @param alightAt          구간 끝 시각(대중교통=정적 하차, 도보=이동 끝). boardAt 과 같은 규칙(provider 의존)
 * @param rideDurationSeconds 정적 승차 소요(초). 초 단위 보존(분 반올림은 legacy mapper 최종단계)
 * @param stops             지나는 정류장 occurrence(승·하차 포함)
 * @param walkSteps         도보 leg 의 turn. 대중교통 leg 는 빈 목록
 * @param missing           원천 부재 속성(가짜값 대신 사유로 노출)
 * @param sourceRouteColor  원천이 준 노선 표시색(GTFS route_color, "#" 없는 6자리 hex). 없으면 null
 * @param distanceMeters    구간 거리(m). 원천이 주면 채운다(OTP leg {@code distance}). 모르면 null — 0 으로 지어내지 않는다
 * @param sourceRouteLongName 원천 노선 긴 이름({@code 서울9호선(급행)<하행>}). 방향·급행 표기를 들고 있다. 없으면 null
 * @param patternCode       원천 trip 패턴 코드(OTP {@code trip.pattern.code}, {@code 1:RR_ACC1_S-1-SD-1D::05}). 없으면 null
 * @param trainKind         철도 구간 열차 종류(subway-express). 어댑터 경계의 {@code RailServiceClassifier} 가 한 번
 *                          채운다. 매퍼는 판정하지 않는다(null). 버스·도보는 언제나 null
 * @param alternatives      같은 승하차 정류장으로 갈 수 있는 다른 버스 노선(otp-bus-lane-merge). 어댑터는 OTP
 *                          {@code nextLegs} 원재료를 싣고, {@link BusLaneMerger} 가 거른 최종 목록으로 바꾼다. 버스가
 *                          아니거나 모르면 빈 목록
 */
public record RoutingLeg(
        RoutingMode mode,
        String sourceDetailType,
        String feedId,
        String agencyId,
        String sourceRouteId,
        String sourceRouteName,
        String sourceRouteDirection,
        String tripId,
        LocalDate serviceDate,
        Instant boardAt,
        Instant alightAt,
        int rideDurationSeconds,
        List<StopOccurrence> stops,
        List<RoutingWalkStep> walkSteps,
        LegGeometry geometry,
        Enrichment enrichment,
        Set<MissingAttribute> missing,
        String sourceRouteColor,
        Double distanceMeters,
        String sourceRouteLongName,
        String patternCode,
        TrainKind trainKind,
        List<AlternativeRoute> alternatives
) {
    /** 대체 노선을 모르는 원천용 하위호환 생성자. 빈 목록이 곧 옛 동작이다. */
    public RoutingLeg(RoutingMode mode, String sourceDetailType, String feedId, String agencyId,
                      String sourceRouteId, String sourceRouteName, String sourceRouteDirection,
                      String tripId, LocalDate serviceDate, Instant boardAt, Instant alightAt,
                      int rideDurationSeconds, List<StopOccurrence> stops,
                      List<RoutingWalkStep> walkSteps, LegGeometry geometry,
                      Enrichment enrichment, Set<MissingAttribute> missing, String sourceRouteColor,
                      Double distanceMeters, String sourceRouteLongName, String patternCode,
                      TrainKind trainKind) {
        this(mode, sourceDetailType, feedId, agencyId, sourceRouteId, sourceRouteName,
                sourceRouteDirection, tripId, serviceDate, boardAt, alightAt, rideDurationSeconds,
                stops, walkSteps, geometry, enrichment, missing, sourceRouteColor, distanceMeters,
                sourceRouteLongName, patternCode, trainKind, List.of());
    }

    /** 노선 긴 이름·패턴·열차 종류를 모르는 원천용 하위호환 생성자. 새 칸을 null 로 두는 것이 곧 옛 동작이다. */
    public RoutingLeg(RoutingMode mode, String sourceDetailType, String feedId, String agencyId,
                      String sourceRouteId, String sourceRouteName, String sourceRouteDirection,
                      String tripId, LocalDate serviceDate, Instant boardAt, Instant alightAt,
                      int rideDurationSeconds, List<StopOccurrence> stops,
                      List<RoutingWalkStep> walkSteps, LegGeometry geometry,
                      Enrichment enrichment, Set<MissingAttribute> missing, String sourceRouteColor,
                      Double distanceMeters) {
        this(mode, sourceDetailType, feedId, agencyId, sourceRouteId, sourceRouteName,
                sourceRouteDirection, tripId, serviceDate, boardAt, alightAt, rideDurationSeconds,
                stops, walkSteps, geometry, enrichment, missing, sourceRouteColor, distanceMeters,
                null, null, null);
    }

    /** 거리를 모르는 원천용 하위호환 생성자. 새 칸을 null 로 두는 것이 곧 옛 동작이다. */
    public RoutingLeg(RoutingMode mode, String sourceDetailType, String feedId, String agencyId,
                      String sourceRouteId, String sourceRouteName, String sourceRouteDirection,
                      String tripId, LocalDate serviceDate, Instant boardAt, Instant alightAt,
                      int rideDurationSeconds, List<StopOccurrence> stops,
                      List<RoutingWalkStep> walkSteps, LegGeometry geometry,
                      Enrichment enrichment, Set<MissingAttribute> missing, String sourceRouteColor) {
        this(mode, sourceDetailType, feedId, agencyId, sourceRouteId, sourceRouteName,
                sourceRouteDirection, tripId, serviceDate, boardAt, alightAt, rideDurationSeconds,
                stops, walkSteps, geometry, enrichment, missing, sourceRouteColor, null);
    }

    /** 색을 모르는 원천용 하위호환 생성자. 새 칸을 null 로 두는 것이 곧 옛 동작이다. */
    public RoutingLeg(RoutingMode mode, String sourceDetailType, String feedId, String agencyId,
                      String sourceRouteId, String sourceRouteName, String sourceRouteDirection,
                      String tripId, LocalDate serviceDate, Instant boardAt, Instant alightAt,
                      int rideDurationSeconds, List<StopOccurrence> stops,
                      List<RoutingWalkStep> walkSteps, LegGeometry geometry,
                      Enrichment enrichment, Set<MissingAttribute> missing) {
        this(mode, sourceDetailType, feedId, agencyId, sourceRouteId, sourceRouteName,
                sourceRouteDirection, tripId, serviceDate, boardAt, alightAt, rideDurationSeconds,
                stops, walkSteps, geometry, enrichment, missing, null);
    }

    /** 방향을 못 읽는 원천용 하위호환 생성자. 새 칸을 null 로 두는 것이 곧 옛 동작이다. */
    public RoutingLeg(RoutingMode mode, String sourceDetailType, String feedId, String agencyId,
                      String sourceRouteId, String sourceRouteName, String tripId,
                      LocalDate serviceDate, Instant boardAt, Instant alightAt,
                      int rideDurationSeconds, List<StopOccurrence> stops,
                      List<RoutingWalkStep> walkSteps, LegGeometry geometry,
                      Enrichment enrichment, Set<MissingAttribute> missing) {
        this(mode, sourceDetailType, feedId, agencyId, sourceRouteId, sourceRouteName, null, tripId,
                serviceDate, boardAt, alightAt, rideDurationSeconds, stops, walkSteps, geometry,
                enrichment, missing, null);
    }

    public RoutingLeg {
        stops = stops == null ? List.of() : List.copyOf(stops);
        walkSteps = walkSteps == null ? List.of() : List.copyOf(walkSteps);
        missing = missing == null ? Set.of() : Set.copyOf(missing);
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }

    public boolean isTransit() {
        return mode == RoutingMode.BUS || mode == RoutingMode.SUBWAY || mode == RoutingMode.RAIL;
    }

    /** enrichment 만 바꾼 복사본(나머지 필드 보존). 수동 전체 재생성 시 신규 필드 유실을 막는다. */
    public RoutingLeg withEnrichment(Enrichment newEnrichment) {
        return new RoutingLeg(mode, sourceDetailType, feedId, agencyId, sourceRouteId, sourceRouteName,
                sourceRouteDirection, tripId, serviceDate, boardAt, alightAt, rideDurationSeconds,
                stops, walkSteps, geometry, newEnrichment, missing, sourceRouteColor, distanceMeters,
                sourceRouteLongName, patternCode, trainKind, alternatives);
    }

    /** 열차 종류만 바꾼 복사본(나머지 필드 보존). */
    public RoutingLeg withTrainKind(TrainKind newTrainKind) {
        return new RoutingLeg(mode, sourceDetailType, feedId, agencyId, sourceRouteId, sourceRouteName,
                sourceRouteDirection, tripId, serviceDate, boardAt, alightAt, rideDurationSeconds,
                stops, walkSteps, geometry, enrichment, missing, sourceRouteColor, distanceMeters,
                sourceRouteLongName, patternCode, newTrainKind, alternatives);
    }

    /** 대체 버스 노선만 바꾼 복사본(나머지 필드 보존). */
    public RoutingLeg withAlternatives(List<AlternativeRoute> newAlternatives) {
        return new RoutingLeg(mode, sourceDetailType, feedId, agencyId, sourceRouteId, sourceRouteName,
                sourceRouteDirection, tripId, serviceDate, boardAt, alightAt, rideDurationSeconds,
                stops, walkSteps, geometry, enrichment, missing, sourceRouteColor, distanceMeters,
                sourceRouteLongName, patternCode, trainKind, newAlternatives);
    }

    /** 승차 정류장 원천 id. 정류장이 없으면 null. */
    public String boardStopId() {
        return stops.isEmpty() ? null : stops.get(0).sourceStopId();
    }

    /** 하차 정류장 원천 id. 정류장이 없으면 null. */
    public String alightStopId() {
        return stops.isEmpty() ? null : stops.get(stops.size() - 1).sourceStopId();
    }
}
