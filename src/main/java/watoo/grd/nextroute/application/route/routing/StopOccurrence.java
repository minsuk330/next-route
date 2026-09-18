package watoo.grd.nextroute.application.route.routing;

import java.time.Instant;

/**
 * 구간이 지나는 정류장 1회 방문. 같은 정류장 재방문은 별도 occurrence 로 저장한다.
 * 원천 stop_sequence 와 구간 내 occurrenceIndex 를 다른 필드로 둔다(서울 API 순번/sectionId 로 간주 금지).
 *
 * <p>OTP GraphQL {@code StopCall} 에는 GTFS stop_sequence 가 없다(그 필드는 다른(legacy) 타입 소속).
 * {@code sourceStopSequence}/{@code tripPattern} 은 그래서 계속 null 로 남을 수 있다 — leg 의
 * tripId + {@code occurrenceIndex} 조합이 실질 식별자다.
 *
 * @param sourceStopId        feed namespace 포함 원천 stop id(문자열 보존)
 * @param tripPattern         OTP pattern 식별(있으면). 방향/회차 구분용
 * @param sourceStopSequence  원천 GTFS stop_sequence(있으면, OTP 쿼리로는 못 채움 — 위 설명 참고)
 * @param occurrenceIndex     이 구간 안에서 몇 번째 방문인지(0-base)
 * @param scheduledArrival    정적 시간표 도착시각(없으면 null)
 * @param scheduledDeparture  정적 시간표 출발시각(없으면 null)
 * @param realtimeArrival     실시간 보강 도착시각(제공 안 되면 null)
 * @param realtimeDeparture   실시간 보강 출발시각(제공 안 되면 null)
 */
public record StopOccurrence(
        String sourceStopId,
        String tripPattern,
        Integer sourceStopSequence,
        int occurrenceIndex,
        double lon,
        double lat,
        String name,
        Instant scheduledArrival,
        Instant scheduledDeparture,
        Instant realtimeArrival,
        Instant realtimeDeparture
) {
    /** 시각 없는 원천(ODsay 등) 호출부 호환용. */
    public StopOccurrence(String sourceStopId, String tripPattern, Integer sourceStopSequence,
                           int occurrenceIndex, double lon, double lat, String name) {
        this(sourceStopId, tripPattern, sourceStopSequence, occurrenceIndex, lon, lat, name,
                null, null, null, null);
    }
}
