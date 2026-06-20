package watoo.grd.nextroute.application.stopselection.dto;

/** 정류장 경유 노선 1건. 동명 노선 구분 위해 type/기점/종점 포함. */
public record StopRouteResult(
        String routeId,
        String routeName,
        String direction,
        Integer routeType,
        String startStation,
        String endStation,
        boolean supportsPrediction
) {}
