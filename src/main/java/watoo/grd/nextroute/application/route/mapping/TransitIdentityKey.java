package watoo.grd.nextroute.application.route.mapping;

/**
 * canonical(GTFS/OTP) 쪽 식별. 같은 정류장 재방문은 occurrenceIndex 로 구분한다.
 * source stop_sequence 와 occurrenceIndex 는 다른 개념이다(서울 API 순번/sectionId 로 간주 금지).
 *
 * @param tripPattern    OTP pattern(있으면). 방향/회차 구분. 없으면 null
 * @param sourceStopSequence 원천 GTFS stop_sequence(있으면). OTP StopCall 엔 없어 보통 null
 */
public record TransitIdentityKey(
        String dataVersion,
        String feedId,
        String agencyId,
        String sourceRouteId,
        String tripPattern,
        String sourceStopId,
        Integer sourceStopSequence,
        int occurrenceIndex
) {
}
