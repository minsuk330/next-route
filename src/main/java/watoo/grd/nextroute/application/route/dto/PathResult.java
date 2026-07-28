package watoo.grd.nextroute.application.route.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record PathResult(
        /**
         * 검색 결과 스냅샷 안에서 이 경로를 가리키는 키({@code paths} 인덱스 문자열).
         * 스냅샷 저장 시점에 {@link #withPathKey}로 채워지고, 저장이 실패하면 null 이라 응답에서 사라진다.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL) String pathKey,
        int pathType,
        PathInfo info,
        List<SubPathResult> subPaths,
        List<LaneGraphicResult> laneGraphics
) {

    /** pathKey 없이 만드는 기존 호출부(어댑터·보강 단계·테스트)용. 스냅샷 저장 시 {@link #withPathKey}로 채운다. */
    public PathResult(int pathType, PathInfo info,
                      List<SubPathResult> subPaths, List<LaneGraphicResult> laneGraphics) {
        this(null, pathType, info, subPaths, laneGraphics);
    }

    public PathResult withPathKey(String pathKey) {
        return new PathResult(pathKey, pathType, info, subPaths, laneGraphics);
    }
}
