package watoo.grd.nextroute.application.route.routing;

/**
 * 구간이 지나는 정류장 1회 방문. 같은 정류장 재방문은 별도 occurrence 로 저장한다.
 * 원천 stop_sequence 와 구간 내 occurrenceIndex 를 다른 필드로 둔다(서울 API 순번/sectionId 로 간주 금지).
 *
 * @param sourceStopId       feed namespace 포함 원천 stop id(문자열 보존)
 * @param tripPattern        OTP pattern 식별(있으면). 방향/회차 구분용
 * @param sourceStopSequence 원천 GTFS stop_sequence(있으면). PR2 OTP query 로 채움
 * @param occurrenceIndex    이 구간 안에서 몇 번째 방문인지(0-base)
 */
public record StopOccurrence(
        String sourceStopId,
        String tripPattern,
        Integer sourceStopSequence,
        int occurrenceIndex,
        double lon,
        double lat,
        String name
) {
}
