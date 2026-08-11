package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposal;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 재탐색 제안 접근.
 *
 * <p>시각 파라미터가 두 종류인 점을 조심할 것 — {@code now}·{@code expiresAt} 은 도메인 시각
 * ({@link Instant}, {@code timestamptz})이고 {@code auditNow} 는 {@code BaseEntity} 의 감사 칼럼
 * ({@link LocalDateTime}, {@code timestamp(6)})이다. 바꿔 써도 컴파일은 되고 값만 조용히 어긋난다
 * ({@link JourneyNotificationRepository} 가 같은 함정을 적어 뒀다).
 *
 * <p>상태를 바꾸는 쿼리는 모두 {@code clearAutomatically = false} 다. 부르는 쪽이 같은 트랜잭션에서
 * 여정 엔티티를 고치고 있는 경로가 있어(여정 종료), 영속성 컨텍스트를 비우면 그 변경이 사라진다.
 */
public interface RerouteProposalRepository extends JpaRepository<RerouteProposal, Long> {

    /** 살아 있는 제안 하나. 만료 전이({@link #resolveStale})를 먼저 돌린 뒤에 부른다. */
    Optional<RerouteProposal> findByJourneyIdAndTransferIndexAndStatusAndDeletedAtIsNull(
            Long journeyId, int transferIndex, RerouteProposalStatus status);

    /** 여정의 살아 있는 제안 전부. 조회 진입점이 환승 번호 없이 "지금 뜬 제안"을 물을 때 쓴다. */
    Optional<RerouteProposal> findFirstByJourneyIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long journeyId, RerouteProposalStatus status);

    /**
     * 만료·개정본 어긋남을 한 문장으로 정리한다.
     *
     * <p>둘을 함께 넘기는 이유 — 수락으로 개정본이 오르면 이전 개정본의 제안은 만료 시각과
     * 무관하게 무의미하다. 남겨 두면 부분 unique 가 새 제안 삽입을 막는다.
     *
     * <p>{@code @Modifying} 이 아니다({@code RETURNING}). 반드시 {@code @Transactional} 안에서
     * 부른다. 행 수 대신 사유를 돌려주는 이유는 지표 때문이다 — {@code ttl} 과
     * {@code route-changed} 는 운영에서 뜻이 전혀 다른데, 한 문장이 {@code CASE} 로 가르므로
     * 부르는 쪽이 개수만 받으면 어느 쪽이 몇 건인지 영영 모른다.
     *
     * @return 정리한 행마다의 사유({@code ttl} · {@code route-changed})
     */
    @Query(value = """
            UPDATE reroute_proposal
               SET status = CASE WHEN route_revision <> :currentRevision
                                 THEN 'SUPERSEDED' ELSE 'EXPIRED' END,
                   resolved_at = :now,
                   resolved_reason = CASE WHEN route_revision <> :currentRevision
                                          THEN 'route-changed' ELSE 'ttl' END,
                   updated_at = :auditNow
             WHERE journey_id = :journeyId
               AND status = 'ACTIVE'
               AND deleted_at IS NULL
               AND (expires_at <= :now OR route_revision <> :currentRevision)
            RETURNING resolved_reason
            """, nativeQuery = true)
    List<String> resolveStale(@Param("journeyId") Long journeyId,
                              @Param("currentRevision") int currentRevision,
                              @Param("now") Instant now,
                              @Param("auditNow") LocalDateTime auditNow);

    /** 같은 환승의 살아 있는 제안을 새 제안에 자리를 내주게 한다. */
    @Modifying(clearAutomatically = false)
    @Query(value = """
            UPDATE reroute_proposal
               SET status = 'SUPERSEDED', resolved_at = :now,
                   resolved_reason = 'replaced', updated_at = :auditNow
             WHERE journey_id = :journeyId AND transfer_index = :transferIndex
               AND status = 'ACTIVE' AND deleted_at IS NULL
            """, nativeQuery = true)
    int supersedeActive(@Param("journeyId") Long journeyId,
                        @Param("transferIndex") int transferIndex,
                        @Param("now") Instant now,
                        @Param("auditNow") LocalDateTime auditNow);

    /**
     * 만료된 {@code ACTIVE} 를 batch 크기만큼 접는다(배치 sweep).
     *
     * <p>{@code SKIP LOCKED} 라 두 인스턴스가 같은 행을 잡지 않는다. lazy 전이와 이유를
     * 가른다({@code ttl-sweep}) — 이쪽이 대부분이면 사용자가 제안을 보지 못하고 있다는 뜻이다.
     */
    @Modifying(clearAutomatically = false)
    @Query(value = """
            UPDATE reroute_proposal
               SET status = 'EXPIRED', resolved_at = :now,
                   resolved_reason = 'ttl-sweep', updated_at = :auditNow
             WHERE id IN (SELECT id FROM reroute_proposal
                           WHERE status = 'ACTIVE' AND deleted_at IS NULL
                             AND expires_at <= :now
                           ORDER BY expires_at
                           LIMIT :batchSize
                           FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int expireDueBatch(@Param("now") Instant now, @Param("batchSize") int batchSize,
                       @Param("auditNow") LocalDateTime auditNow);

    /**
     * 여정 종료 시 살아 있는 제안을 접는다.
     *
     * <p>스위치를 걸지 않는다 — 기능 플래그가 꺼진 동안 쌓인 행이라도 여정이 끝나면 접혀야 한다.
     * 남겨 두면 수락 API 가 "여정은 끝났는데 제안은 유효한" 상태를 본다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("""
            update RerouteProposal p
               set p.status = watoo.grd.nextroute.domain.journey.entity.RerouteProposalStatus.SUPERSEDED,
                   p.resolvedAt = :now,
                   p.resolvedReason = 'journey-ended'
             where p.journeyId = :journeyId
               and p.status = watoo.grd.nextroute.domain.journey.entity.RerouteProposalStatus.ACTIVE
               and p.deletedAt is null
            """)
    int resolveActiveByJourneyId(@Param("journeyId") Long journeyId, @Param("now") Instant now);

    /** lease 만료 배치용. 만료된 여정 묶음의 살아 있는 제안을 한 문장으로 접는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("""
            update RerouteProposal p
               set p.status = watoo.grd.nextroute.domain.journey.entity.RerouteProposalStatus.SUPERSEDED,
                   p.resolvedAt = :now,
                   p.resolvedReason = 'journey-ended'
             where p.journeyId in :journeyIds
               and p.status = watoo.grd.nextroute.domain.journey.entity.RerouteProposalStatus.ACTIVE
               and p.deletedAt is null
            """)
    int resolveActiveByJourneyIdIn(@Param("journeyIds") Collection<Long> journeyIds,
                                   @Param("now") Instant now);
}
