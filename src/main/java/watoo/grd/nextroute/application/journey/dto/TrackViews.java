package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.application.route.routing.RoutingMode;

/**
 * 단독 추적(#265) 선택 화면 응답. 정류장 → 방향 → 하차 후보 순서로 고른다.
 */
public final class TrackViews {

    private TrackViews() {
    }

    /**
     * 좌표 주변 정류장/역. {@code gtfsStopId} 가 이후 모든 호출의 키다.
     *
     * <p>실시간 조회 id(#313)는 못 풀면 null 이다 — 앱은 그때 도착 정보 없이 노선 선택·추적만 보여 준다.
     * 지하철 정류장을 지나는 호선이 둘 이상이면 호선마다 한 줄이다.
     *
     * @param arrivalStopId    버스 도착 조회 정류장 id(서울 9자리 / 경기 GBIS 9자리 / 그 밖 TAGO 접두사 포함)
     * @param arsId            서울 정류장 번호. 서울 밖이면 null
     * @param arrivalStationId 지하철 역 도착 조회 id(서울 실시간 {@code statn_id})
     */
    public record Stop(String gtfsStopId, String name, double lat, double lon, RoutingMode mode,
                       Integer distanceMeters, String arrivalStopId, String arsId,
                       String arrivalStationId, String lineId, String lineName) {
    }

    /**
     * 그 정류장에서 탈 수 있는 노선의 한 방향.
     *
     * @param directionLabel headsign, 순환 지하철이면 내선·외선, 그 밖은 "{종점} 방면"
     * @param patternCode    하차 후보 조회·시작에 힌트로 넘긴다. 그래프가 바뀌면 낡을 수 있고, 그래도 서버가 노선으로 다시 찾는다
     * @param color          "#" 붙은 표시색. 없으면 null
     * @param arrivalRouteId 버스 도착 조회 노선 id(#313). 지하철·못 풀면 null
     */
    public record Direction(String routeGtfsId, RoutingMode mode, String routeName, String color,
                            String directionLabel, String terminalName, String patternCode,
                            String arrivalRouteId) {
    }

    /** 하차 후보. {@code index} 는 승차 다음 정류장이 0 이다. */
    public record AlightStop(String gtfsStopId, String name, double lat, double lon, int index) {
    }
}
