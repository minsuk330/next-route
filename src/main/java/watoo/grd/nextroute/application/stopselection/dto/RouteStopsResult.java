package watoo.grd.nextroute.application.stopselection.dto;

import java.util.List;

/** 노선 경유 정류장 목록 (지도 핀용 좌표 포함) + 노선 단위 예측 지원 여부. */
public record RouteStopsResult(
        String routeId,
        boolean supportsPrediction,
        List<RouteStop> stops
) {
    /** 좌표는 nullable — null이면 UI가 핀 생략. */
    public record RouteStop(
            Integer seq,
            String stopId,
            String stopName,
            Double latitude,
            Double longitude,
            String direction
    ) {}
}
