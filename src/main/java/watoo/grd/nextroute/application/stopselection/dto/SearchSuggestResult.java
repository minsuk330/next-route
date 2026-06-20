package watoo.grd.nextroute.application.stopselection.dto;

import java.util.List;

/** 통합 자동완성 결과 (버스번호 + 정류장명 혼합). */
public record SearchSuggestResult(
        List<SuggestRoute> routes,
        List<SuggestStop> stops
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
        return new SearchSuggestResult(List.of(), List.of());
    }
}
