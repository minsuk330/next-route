package watoo.grd.nextroute.application.stopselection.dto;

import java.util.List;

/** 통합 자동완성 결과 (버스번호 + 정류장명 혼합). */
public record SearchSuggestResult(
        List<SuggestRoute> routes,
        List<SuggestStop> stops,
        /** route 매치가 하나로 좁혀졌을 때만 그 노선의 경유 정류장(seq 순) 채움. 아니면 빈 리스트. */
        List<RouteStopsResult.RouteStop> routeStops
) {
    public record SuggestRoute(
            String routeId,
            String routeName,
            Integer routeType,
            String startStation,
            String endStation,
            boolean supportsPrediction
    ) {}

    /** 좌표는 nullable. */
    public record SuggestStop(
            String stopId,
            String stopName,
            String arsId,
            Double latitude,
            Double longitude
    ) {}

    public static SearchSuggestResult empty() {
        return new SearchSuggestResult(List.of(), List.of(), List.of());
    }
}
