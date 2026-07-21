package watoo.grd.nextroute.application.route.port.in;

import watoo.grd.nextroute.application.route.dto.SearchHistoryResponse;

import java.util.List;

/** 본인 경로 검색 이력을 최신순으로 조회한다. */
public interface GetSearchHistoryUseCase {

    List<SearchHistoryResponse> getAll(Long userId);
}
