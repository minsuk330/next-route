package watoo.grd.nextroute.application.route.port.in;

import watoo.grd.nextroute.application.route.dto.RecentPlaceResponse;

import java.util.List;

/** 본인 검색에 등장한 최근 장소를 좌표 dedup 후 최신순으로 조회한다. */
public interface GetSearchHistoryUseCase {

    List<RecentPlaceResponse> getAll(Long userId);
}
