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
 * <h2>시각은 시간표뿐이다</h2>
 * <p>이 레코드는 <b>정적 시간표 시각만</b> 든다. 배포된 OTP 에는 실시간 피드(GTFS-RT)가 물려 있지
 * 않아서(router-config.json 자체가 없고 build-config 의 feed 는 static GTFS 하나뿐) OTP 는 "지금 저
 * 차가 어디 있는지"를 원천적으로 모른다. 실시간은 서버가 따로 가진 버스 도착/위치 캐시가 소유하고,
 * 그 보정은 정류장 단위가 아니라 <b>탑승 지점 단위</b>로 얹는다(재탐색 overlay) — 애초에 서울 버스
 * 도착 API 가 주는 것이 "이 정류장에 이 노선이 몇 분 뒤"이지, 한 운행의 모든 하류 정류장 시각이
 * 아니기 때문이다. 그래서 여기에 실시간 칸을 두면 영원히 빈 칸이 된다.
 *
 * @param sourceStopId        feed namespace 포함 원천 stop id(문자열 보존)
 * @param tripPattern         OTP pattern 식별(있으면). 방향/회차 구분용
 * @param sourceStopSequence  원천 GTFS stop_sequence(있으면, OTP 쿼리로는 못 채움 — 위 설명 참고)
 * @param occurrenceIndex     이 구간 안에서 몇 번째 방문인지(0-base)
 * @param scheduledArrival    정적 시간표 도착시각(없으면 null)
 * @param scheduledDeparture  정적 시간표 출발시각(없으면 null)
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
        Instant scheduledDeparture
) {
    /** 시각 없는 원천(ODsay 등) 호출부 호환용. */
    public StopOccurrence(String sourceStopId, String tripPattern, Integer sourceStopSequence,
                           int occurrenceIndex, double lon, double lat, String name) {
        this(sourceStopId, tripPattern, sourceStopSequence, occurrenceIndex, lon, lat, name,
                null, null);
    }
}
