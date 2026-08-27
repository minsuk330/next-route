package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface JourneyLegRouteOptionRepository
        extends JpaRepository<JourneyLegRouteOption, Long> {

    /**
     * 여러 단계의 노선 후보를 한 번에.
     *
     * <p>단계마다 따로 조회하면 단계 수만큼 쿼리가 나간다. 단계를 한 번, 후보를 한 번 읽고
     * 메모리에서 조립하는 것이 이 표의 사용 방식이다.
     */
    List<JourneyLegRouteOption> findByJourneyLegIdInAndDeletedAtIsNull(
            Collection<Long> journeyLegIds);

    /**
     * 탈퇴: 그 사용자의 여정 단계에 매달린 노선 후보를 소프트 삭제한다(ADR 0001).
     *
     * <p>후보는 단계에 매달리고 단계는 여정에 매달려, 2단 서브쿼리로 사용자까지 거슬러 오른다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update JourneyLegRouteOption o set o.deletedAt = :now
             where o.journeyLegId in (
                       select l.id from JourneyLeg l
                        where l.journeyId in (select s.id from JourneySession s where s.userId = :userId))
               and o.deletedAt is null
            """)
    int softDeleteByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
