package watoo.grd.nextroute.application.route.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

public record RouteSearchResult(
        int searchType,
        int busCount,
        int subwayCount,
        int trainCount,
        int airCount,
        List<PathResult> paths,
        /** 응답 완전성 계약(Step 5). 컨트롤러 반환 직전에만 채워지고, 내부 중간 결과에서는 null. */
        @JsonInclude(JsonInclude.Include.NON_NULL) SearchMeta meta,
        /** 이 검색 결과 스냅샷의 식별자(서버가 만든 UUID). 스냅샷 저장에 실패하면 null 이라 응답에서 사라진다. */
        @JsonInclude(JsonInclude.Include.NON_NULL) String searchSessionId,
        /** 스냅샷이 만료되는 시각. {@code searchSessionId} 와 함께 채워진다. */
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant expiresAt
) {

    /** meta 없는 기존 호출부(보강 중간 결과·어댑터·테스트)용. meta 는 컨트롤러 직전에 {@link #withMeta} 로 채운다. */
    public RouteSearchResult(int searchType, int busCount, int subwayCount,
                             int trainCount, int airCount, List<PathResult> paths) {
        this(searchType, busCount, subwayCount, trainCount, airCount, paths, null, null, null);
    }

    /** 스냅샷 필드 없는 호출부용. 스냅샷은 컨트롤러 직전에 {@link #withSnapshot} 으로 채운다. */
    public RouteSearchResult(int searchType, int busCount, int subwayCount,
                             int trainCount, int airCount, List<PathResult> paths, SearchMeta meta) {
        this(searchType, busCount, subwayCount, trainCount, airCount, paths, meta, null, null);
    }

    public RouteSearchResult withMeta(SearchMeta meta) {
        return new RouteSearchResult(searchType, busCount, subwayCount, trainCount, airCount,
                paths, meta, searchSessionId, expiresAt);
    }

    /**
     * {@code paths} 각 원소에 인덱스 기반 {@code pathKey}("0", "1", …)를 채운 사본을 만든다.
     * 스냅샷에 저장되는 값과 응답으로 나가는 값이 같은 키를 갖게 하려고 저장 직전에 한 번 적용한다.
     */
    public RouteSearchResult withPathKeys() {
        if (paths == null || paths.isEmpty()) {
            return this;
        }
        List<PathResult> keyed = IntStream.range(0, paths.size())
                .mapToObj(i -> paths.get(i).withPathKey(String.valueOf(i)))
                .toList();
        return new RouteSearchResult(searchType, busCount, subwayCount, trainCount, airCount,
                keyed, meta, searchSessionId, expiresAt);
    }

    public RouteSearchResult withSnapshot(String searchSessionId, Instant expiresAt) {
        return new RouteSearchResult(searchType, busCount, subwayCount, trainCount, airCount,
                paths, meta, searchSessionId, expiresAt);
    }
}
