package watoo.grd.nextroute.domain.route.log.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.route.log.entity.RouteSearchHistory;

import java.time.LocalDateTime;

public interface RouteSearchHistoryRepository extends JpaRepository<RouteSearchHistory, Long> {

    /**
     * 좌표(user_id + 4좌표)당 1행으로 저장한다. 동일 좌표 재검색이면 새 행을 만들지 않고
     * searched_at·pathCount·searchType·이름을 최신 검색값으로 갱신한다(dedup collapse).
     * 단일 {@code INSERT ... ON CONFLICT DO UPDATE} 라 동시 동일좌표 insert 도 원자적으로 처리된다.
     */
    @Modifying
    @Query(value = """
            insert into route_search_history
                (user_id, startx, starty, endx, endy, start_name, end_name,
                 path_count, search_type, searched_at, created_at, updated_at)
            values (:userId, :sx, :sy, :ex, :ey, :sName, :eName,
                    :pathCount, :searchType, :at, :at, :at)
            on conflict (user_id, startx, starty, endx, endy)
            do update set searched_at = excluded.searched_at,
                          path_count  = excluded.path_count,
                          search_type = excluded.search_type,
                          start_name  = excluded.start_name,
                          end_name    = excluded.end_name,
                          updated_at  = excluded.searched_at
            """, nativeQuery = true)
    int upsert(@Param("userId") Long userId,
               @Param("sx") double startX, @Param("sy") double startY,
               @Param("ex") double endX, @Param("ey") double endY,
               @Param("sName") String startName, @Param("eName") String endName,
               @Param("pathCount") Integer pathCount, @Param("searchType") Integer searchType,
               @Param("at") LocalDateTime searchedAt);

    /**
     * 유저당 최근 maxPerUser 행만 남기고 오래된 이력을 물리삭제한다. 정렬·tie-breaker 는
     * 목록 조회와 동일한 (searched_at DESC, id DESC).
     */
    @Modifying
    @Query(value = """
            delete from route_search_history
            where user_id = :userId
              and id not in (
                select id from route_search_history
                where user_id = :userId
                order by searched_at desc, id desc
                limit :maxPerUser)
            """, nativeQuery = true)
    int trim(@Param("userId") Long userId, @Param("maxPerUser") int maxPerUser);
}
