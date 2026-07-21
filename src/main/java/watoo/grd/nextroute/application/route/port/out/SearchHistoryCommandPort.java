package watoo.grd.nextroute.application.route.port.out;

import watoo.grd.nextroute.application.route.dto.SearchHistoryEntry;

/**
 * 검색 이력 쓰기 포트(중간 두께). upsert(dedup collapse)와 trim(유저당 cap)을 한 번의 원자적
 * 연산으로 노출한다. 사전조회 없이 단일 upsert 로 race 를 제거한다.
 */
public interface SearchHistoryCommandPort {

    void upsertAndTrim(SearchHistoryEntry entry, int maxPerUser);
}
