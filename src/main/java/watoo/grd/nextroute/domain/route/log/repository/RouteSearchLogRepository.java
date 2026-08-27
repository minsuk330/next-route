package watoo.grd.nextroute.domain.route.log.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.route.log.entity.RouteSearchLog;

public interface RouteSearchLogRepository extends JpaRepository<RouteSearchLog, Long> {

    /**
     * 탈퇴: 그 사용자의 검색 로그를 물리 삭제한다. 검색 이력과 응답 원문이 담겨 있어 남기지 않는다
     * (ADR 0001). {@code user_id} 는 nullable 이라 익명 로그는 대상이 아니다.
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from RouteSearchLog l where l.userId.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
