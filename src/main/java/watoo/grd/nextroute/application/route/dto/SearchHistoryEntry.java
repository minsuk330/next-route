package watoo.grd.nextroute.application.route.dto;

import java.time.LocalDateTime;

/**
 * 검색 이력 저장 단위. request/result DTO 나 엔티티를 out 포트에 노출하지 않기 위한
 * persistence-neutral 값 객체다(포트는 이 record 만 안다).
 */
public record SearchHistoryEntry(
        Long userId,
        double startX,
        double startY,
        double endX,
        double endY,
        String startName,
        String endName,
        Integer pathCount,
        Integer searchType,
        LocalDateTime searchedAt
) {

    public static SearchHistoryEntry of(Long userId, RouteSearchRequest request,
                                        RouteSearchResult result, LocalDateTime at) {
        return new SearchHistoryEntry(
                userId,
                request.startX(), request.startY(), request.endX(), request.endY(),
                request.startName(), request.endName(),
                result.paths() != null ? result.paths().size() : 0,
                result.searchType(),
                at);
    }
}
