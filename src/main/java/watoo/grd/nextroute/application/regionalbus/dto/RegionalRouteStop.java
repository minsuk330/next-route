package watoo.grd.nextroute.application.regionalbus.dto;

/**
 * 노선 하나의 정류장 한 칸. {@code seq} 는 실시간 API 순번이다(경기 {@code stationSeq}, TAGO {@code nodeord}).
 *
 * @param turn     회차 지점인가. 경기만 준다
 * @param upDown   TAGO {@code updowncd}. 경기는 null
 * @param waypoint 승객이 못 타는 경유 가상점인가. 경기는 이름의 {@code (경유)}, TAGO 는 판별 전이라 null
 */
public record RegionalRouteStop(
        String stopId,
        String stopName,
        int seq,
        Boolean turn,
        Integer upDown,
        Boolean waypoint,
        Double lat,
        Double lon
) {
}
