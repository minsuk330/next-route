package watoo.grd.nextroute.application.route.port.in;

import watoo.grd.nextroute.application.route.dto.RecentPlaceResponse;

import java.util.List;

/** 사용자의 검색 이력을 조회·삭제·초기화한다. */
public interface SearchHistoryUseCase {
    List<RecentPlaceResponse> getAll(Long userId);

    void delete(Long userId, Long id);

    void clear(Long userId);
}
