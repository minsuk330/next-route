package watoo.grd.nextroute.domain.route.log.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.route.log.entity.RouteSearchEvent;

import java.time.Instant;

public interface RouteSearchEventRepository extends JpaRepository<RouteSearchEvent, Long> {

    /**
     * 검색 한 건. 같은 스냅샷 id 가 다시 오면(재시도 등) 0행.
     *
     * @return 넣은 행 수(0 또는 1)
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO route_search_event (search_session_id, user_id, searched_at, outcome, path_count, latency_ms)
            VALUES (:searchSessionId, :userId, :searchedAt, :outcome, :pathCount, :latencyMs)
            ON CONFLICT (search_session_id) WHERE search_session_id IS NOT NULL DO NOTHING
            """, nativeQuery = true)
    int insert(@Param("searchSessionId") String searchSessionId, @Param("userId") Long userId,
               @Param("searchedAt") Instant searchedAt, @Param("outcome") String outcome,
               @Param("pathCount") int pathCount, @Param("latencyMs") int latencyMs);

    /**
     * 보관 기간이 지난 행을 한 묶음 지운다. 한 번에 지우면 긴 잠금·WAL 폭증이 나서 묶음으로 나눈다.
     *
     * @return 지운 행 수. {@code limit} 보다 작으면 더 지울 것이 없다
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM route_search_event
             WHERE id IN (SELECT id FROM route_search_event WHERE searched_at < :cutoff ORDER BY id LIMIT :limit)
            """, nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** 탈퇴: 검색 기록을 물리 삭제한다(ADR 0001 — 개인 이력). */
    @Modifying(clearAutomatically = true)
    @Query("delete from RouteSearchEvent e where e.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
