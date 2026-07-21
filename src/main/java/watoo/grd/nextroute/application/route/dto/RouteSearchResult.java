package watoo.grd.nextroute.application.route.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record RouteSearchResult(
        int searchType,
        int busCount,
        int subwayCount,
        int trainCount,
        int airCount,
        List<PathResult> paths,
        /** 응답 완전성 계약(Step 5). 컨트롤러 반환 직전에만 채워지고, 내부 중간 결과에서는 null. */
        @JsonInclude(JsonInclude.Include.NON_NULL) SearchMeta meta
) {

    /** meta 없는 기존 호출부(보강 중간 결과·어댑터·테스트)용. meta 는 컨트롤러 직전에 {@link #withMeta} 로 채운다. */
    public RouteSearchResult(int searchType, int busCount, int subwayCount,
                             int trainCount, int airCount, List<PathResult> paths) {
        this(searchType, busCount, subwayCount, trainCount, airCount, paths, null);
    }

    public RouteSearchResult withMeta(SearchMeta meta) {
        return new RouteSearchResult(searchType, busCount, subwayCount, trainCount, airCount, paths, meta);
    }
}
