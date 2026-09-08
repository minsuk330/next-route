package watoo.grd.nextroute.application.route.mapping;

/**
 * 서울 지하철 식별. OTP 지하철 stop id 는 DB statn_id 와 대응규칙이 없어(지선코드·동명역 문제)
 * <b>좌표(StopOccurrence lon/lat)로 최근접 subway_station</b> + route 의 호선(1~9)으로 확정한다.
 *
 * @param distanceMeters OTP 좌표 ↔ 매칭된 역 좌표 거리(m). 검증/관측용
 */
public record SeoulSubwayIdentity(String stationId, String lineId, double distanceMeters)
        implements SeoulTransitIdentity {
}
