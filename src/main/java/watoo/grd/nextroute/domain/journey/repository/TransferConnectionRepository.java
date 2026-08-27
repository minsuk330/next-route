package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TransferConnectionRepository extends JpaRepository<TransferConnection, Long> {

    List<TransferConnection> findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
            Long journeyId, int routeRevision);

    /**
     * 환승 하나. 발송 직전 재확인이 접근 알림의 근거가 아직 살아 있는지 볼 때 쓴다.
     *
     * <p>목록을 통째로 읽지 않는다 — 알림 한 건이 보는 것은 자기 환승 하나뿐이다.
     */
    Optional<TransferConnection> findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
            Long journeyId, int routeRevision, int transferIndex);

    /**
     * 탈퇴: 그 사용자의 여정에 속한 환승을 소프트 삭제한다(ADR 0001).
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update TransferConnection t set t.deletedAt = :now
             where t.journeyId in (select s.id from JourneySession s where s.userId = :userId)
               and t.deletedAt is null
            """)
    int softDeleteByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
