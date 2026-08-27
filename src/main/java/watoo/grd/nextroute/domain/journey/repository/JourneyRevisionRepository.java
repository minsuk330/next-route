package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevision;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevisionStatus;

import java.time.LocalDateTime;
import java.util.Optional;

public interface JourneyRevisionRepository extends JpaRepository<JourneyRevision, Long> {

    /** 지금 안내 중인 경로. {@code uq_journey_active_revision} 덕분에 최대 한 건이다. */
    Optional<JourneyRevision> findByJourneyIdAndStatusAndDeletedAtIsNull(
            Long journeyId, JourneyRevisionStatus status);

    /**
     * <b>번호로</b> 개정본을 집는다. 상태(ACTIVE)로 집는 것과 다른 뜻이다.
     *
     * <p>후보를 만드는 동안 다른 요청이 제안을 수락하면 개정본이 오른다. 그때 "지금 ACTIVE"를
     * 읽으면 <b>옛 단계·환승과 새 스냅샷의 좌표가 섞인다</b> — 저장 단계의 잠금 재확인은 DB 쓰기만
     * 막고, 이미 나간 유료 검색과 캐시 오염은 되돌리지 못한다.
     */
    Optional<JourneyRevision> findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(
            Long journeyId, int routeRevision);

    /**
     * 탈퇴: 그 사용자의 여정에 속한 개정본을 소프트 삭제한다(ADR 0001).
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update JourneyRevision r set r.deletedAt = :now
             where r.journeyId in (select s.id from JourneySession s where s.userId = :userId)
               and r.deletedAt is null
            """)
    int softDeleteByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
