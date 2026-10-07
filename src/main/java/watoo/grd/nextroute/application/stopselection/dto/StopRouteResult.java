package watoo.grd.nextroute.application.stopselection.dto;

/** 정류장 경유 노선 1건. 동명 노선 구분 위해 type/기점/종점 포함. */
public record StopRouteResult(
        String routeId,
        String routeName,
        String direction,
        Integer routeType,
        String startStation,
        String endStation,
        boolean supportsPrediction,
        /** OTP GTFS 노선 id(#298). 공항버스 방향 접미사(_U/_D)는 붙지 않는다 — 시작이 흡수 */
        String routeGtfsId
) {}
