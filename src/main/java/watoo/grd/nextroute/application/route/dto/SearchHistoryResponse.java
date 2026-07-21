package watoo.grd.nextroute.application.route.dto;

import watoo.grd.nextroute.domain.route.log.entity.RouteSearchHistory;

import java.time.LocalDateTime;

/**
 * 검색 이력 목록 응답 항목. 재검색은 좌표로 다시 호출하므로 폴리라인 등 결과 상세는 담지 않는다.
 */
public record SearchHistoryResponse(
        Long id,
        Double startX,
        Double startY,
        Double endX,
        Double endY,
        String startName,
        String endName,
        Integer pathCount,
        Integer searchType,
        LocalDateTime searchedAt
) {

    public static SearchHistoryResponse from(RouteSearchHistory h) {
        return new SearchHistoryResponse(
                h.getId(),
                h.getStartX(), h.getStartY(), h.getEndX(), h.getEndY(),
                h.getStartName(), h.getEndName(),
                h.getPathCount(), h.getSearchType(),
                h.getSearchedAt());
    }
}
