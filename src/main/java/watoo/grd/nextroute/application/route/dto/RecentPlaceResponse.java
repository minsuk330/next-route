package watoo.grd.nextroute.application.route.dto;

import java.time.LocalDateTime;

/**
 * 최근 검색에서 등장한 장소(좌표 dedup). 출발/도착 구분 없이 평탄화한 단위다.
 *
 * <p>id 는 그 좌표가 최근 등장한 이력행 id(삭제 대상). 재검색은 좌표로 다시 호출하므로
 * 폴리라인 등 결과 상세는 담지 않는다. lat=Y(startY/endY), lng=X(startX/endX) 매핑이다.
 */
public record RecentPlaceResponse(
        Long id,
        Double lat,
        Double lng,
        String name,
        LocalDateTime searchedAt
) {}
