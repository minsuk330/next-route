package watoo.grd.nextroute.application.route.port.out;

import watoo.grd.nextroute.domain.route.log.entity.RouteSearchHistory;

import java.util.List;

/** 검색 이력 조회·삭제 포트(PR2). 어댑터는 PR1 repository 를 재사용한다. */
public interface SearchHistoryManagementPort {

    List<RouteSearchHistory> findByUser(Long userId);

    /** 본인 소유 행만 삭제하고 삭제된 행 수를 반환한다(0 → 미소유/부재). */
    long deleteByIdAndUser(Long id, Long userId);

    void deleteByUser(Long userId);
}
