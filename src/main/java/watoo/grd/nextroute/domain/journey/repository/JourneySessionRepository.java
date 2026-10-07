package watoo.grd.nextroute.domain.journey.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 여정 조회.
 *
 * <p>이 저장소는 지운 표시가 된 행을 자동으로 걸러 주는 장치를 쓰지 않는다. 쿼리마다
 * {@code DeletedAtIsNull} 을 직접 붙이는 관례이므로 처음부터 조건을 포함해 만든다.
 */
public interface JourneySessionRepository extends JpaRepository<JourneySession, Long> {

    /** 진행 중인 여정 찾기. {@code uq_journey_active_per_user} 덕분에 최대 한 건이다. */
    Optional<JourneySession> findByUserIdAndStatusAndDeletedAtIsNull(
            Long userId, JourneyStatus status);

    /** 남의 여정을 못 보게 소유자까지 조건에 넣는다. 조회 후 비교로 미루지 않는다. */
    Optional<JourneySession> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    /**
     * 여정 행을 잠그고 읽는다. 같은 여정에 온 명령을 한 줄로 세운다.
     *
     * <p>명령 처리는 "지금 상태를 읽고 → 바뀔 내용을 정하고 → 쓴다" 세 걸음이다. 사용자가 버튼을
     * 두 번 누르거나 앱이 재전송하면 두 요청이 같은 상태를 읽고 둘 다 반영하려 든다. 잠금이
     * 그 사이를 막는다.
     *
     * <p>잠그는 대상이 여정 한 행이라 다른 사람의 여정은 물론, <b>같은 사람의 다른 여정</b>도
     * 방해받지 않는다. 반드시 트랜잭션 안에서 호출한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select j from JourneySession j
            where j.id = :id and j.userId = :userId and j.deletedAt is null
            """)
    Optional<JourneySession> findByIdAndUserIdForUpdate(@Param("id") Long id,
                                                        @Param("userId") Long userId);

    /**
     * 여정이 <b>아직 그 개정본으로 살아 있는지</b> 확인하며 공유 잠금을 잡는다.
     *
     * <p>배경 판정기가 쓰는 자리다. 대상을 고른 뒤 외부(Redis)를 다녀오는 동안 여정이 끝나거나
     * 재탐색으로 개정본이 오를 수 있다. 조건만 다시 읽으면 그 확인과 쓰기 사이가 또 벌어지므로,
     * 잠금으로 순서를 정한다 — 종료·재탐색 접점은 이 행을 {@code UPDATE} 하므로(배타 잠금)
     * 둘이 서로 기다린다.
     *
     * <p>{@code FOR SHARE} 인 이유: 판정기는 이 행을 바꾸지 않고 "살아 있음"만 붙잡으면 된다.
     * 배타로 잡으면 같은 여정의 다른 판정끼리도 줄을 선다.
     *
     * <p><b>잠금 순서는 항상 {@code journey_session} → {@code subway_leg_binding} 이다.</b>
     * 뒤집는 경로를 넣으면 데드락이 생긴다.
     *
     * @return 조건을 만족하면 그 여정 번호 1건, 아니면 빈 목록
     */
    @Query(value = """
            SELECT s.id FROM journey_session s
             WHERE s.id = :journeyId
               AND s.status = 'ACTIVE'
               AND s.deleted_at IS NULL
               AND s.route_revision = :routeRevision
             FOR SHARE
            """, nativeQuery = true)
    List<Long> lockActiveRevisionForShare(@Param("journeyId") Long journeyId,
                                          @Param("routeRevision") int routeRevision);

    /**
     * 평가 번호만 하나 올린다. 진행 중인 여정에만 적용한다.
     *
     * <p>{@code commandVersion} 은 건드리지 않는다. progress 는 명령이 아니라 관측이라서, 이것으로
     * 낙관적 락이 깨지면 앱이 보낸 정상 명령이 충돌로 거절된다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update JourneySession j
               set j.evaluationVersion = j.evaluationVersion + 1
             where j.id = :id and j.status = watoo.grd.nextroute.domain.journey.entity.JourneyStatus.ACTIVE
               and j.routeRevision = :routeRevision
               and j.deletedAt is null
            """)
    int bumpEvaluationVersion(@Param("id") Long id, @Param("routeRevision") int routeRevision);

    /**
     * 알렸는데 답이 없었던 횟수를 올린다(PR-3 알림 계약).
     *
     * <p><b>벌크 update 인 이유</b>: 부르는 자리가 만료 경로(lazy·sweep)라 여정 엔티티를 관리
     * 상태로 들고 있지 않다. 여기서 엔티티를 읽어 고치면 sweep 한 배치가 여정 수만큼 SELECT 를
     * 더 돈다. {@code clearAutomatically = false} 는 이 저장소의 관례와 같다 — 같은 트랜잭션에서
     * 다른 엔티티를 dirty-checking 으로 고치는 경로가 있다.
     *
     * @param delta 이번에 센 무응답 수. 한 번에 여러 건이 만료될 수 있다
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("""
            update JourneySession j
               set j.rerouteIgnoredCount = j.rerouteIgnoredCount + :delta
             where j.id = :id and j.deletedAt is null
            """)
    int bumpRerouteIgnored(@Param("id") Long id, @Param("delta") int delta);

    /**
     * lease 를 연장한다. 마지막 신호가 {@code coalesceBefore} 보다 오래됐을 때만 쓴다.
     *
     * <p>progress 는 15초(하차 임박 5초)마다 들어오는데 그때마다 이 행을 쓰면 여정 하나가
     * 계속 갱신되는 hot row 가 된다. 실제 쓰기를 여정별 최대 60초에 한 번으로 합치는 이유다.
     * lease 자체는 3분이라 60초 간격으로 늘려도 만료되지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update JourneySession j
               set j.lastHeartbeatAt = :now, j.guidanceLeaseUntil = :leaseUntil
             where j.id = :id and j.status = watoo.grd.nextroute.domain.journey.entity.JourneyStatus.ACTIVE
               and j.deletedAt is null
               and j.lastHeartbeatAt < :coalesceBefore
            """)
    int extendLeaseIfStale(@Param("id") Long id,
                           @Param("now") Instant now,
                           @Param("leaseUntil") Instant leaseUntil,
                           @Param("coalesceBefore") Instant coalesceBefore);

    /**
     * 소식이 끊긴 여정을 한 번에 여러 건 골라 {@code EXPIRED} 로 바꾼다.
     *
     * <p>고르기와 바꾸기를 한 문장으로 한다. 두 문장으로 나누면 그 사이에 다른 인스턴스가 같은
     * 여정을 골라 두 번 접는다. {@code SKIP LOCKED} 는 이미 다른 인스턴스가 잡은 행을 기다리지
     * 않고 건너뛴다 — 기다리면 워커가 서로를 막는다.
     *
     * <p>고르는 조건이 두 갈래다.
     * <ul>
     *   <li>{@code guidance_lease_until < :leaseDeadline} — lease 3분이 지나고 유예 2분까지 지났다.
     *       유예를 빼서 계산한 시각을 넘겨받는 이유는 {@code idx_journey_expiration} 이 이 칼럼을
     *       그대로 훑을 수 있어야 하기 때문이다. 쿼리 안에서 칼럼에 유예를 더하면 인덱스를 못 쓴다</li>
     *   <li>지하철 보호 시각이 남아 있으면 접지 않는다(SC-12 4항). 터널에서 GPS 가 끊긴 것과 앱이
     *       죽은 것을 서버는 구분할 수 없으므로, 보호 시각까지는 살아 있다고 본다</li>
     * </ul>
     *
     * <p>바꾸는 동시에 {@code next_evaluation_at} 을 비운다. 접은 여정이 평가 대상에 남아 있으면
     * 이미 끝난 여정 때문에 외부 API 를 쓴다. 같은 문장 안에서 해야 그 틈이 생기지 않는다.
     *
     * <p>{@code ACTIVE} 만 고르므로 여러 번 돌려도 결과가 같다(멱등). {@code updated_at} 은
     * 건드리지 않는다 — {@code extendLeaseIfStale}·{@code bumpEvaluationVersion} 과 같은 관례다.
     *
     * <p>{@code @Modifying} 이 아니다({@code RETURNING} 이 결과를 돌려준다). 반드시
     * {@code @Transactional} 안에서 부른다.
     *
     * @param leaseDeadline {@code now - expirationGrace}
     * @return 이번에 접은 여정 번호
     */
    @Query(value = """
            WITH picked AS (
                SELECT s.id FROM journey_session s
                WHERE s.status = 'ACTIVE' AND s.deleted_at IS NULL
                  AND s.guidance_lease_until < :leaseDeadline
                  AND (s.subway_offline_protection_until IS NULL
                       OR s.subway_offline_protection_until < :now)
                ORDER BY s.guidance_lease_until
                LIMIT :batchSize
                FOR UPDATE OF s SKIP LOCKED)
            UPDATE journey_session j
               SET status = 'EXPIRED',
                   end_reason = 'CLIENT_LEASE_EXPIRED',
                   ended_at = :now,
                   next_evaluation_at = NULL,
                   subway_offline_protection_until = NULL
            FROM picked
            WHERE j.id = picked.id
            RETURNING j.id
            """, nativeQuery = true)
    List<Long> claimExpiredBatch(@Param("now") Instant now,
                                 @Param("leaseDeadline") Instant leaseDeadline,
                                 @Param("batchSize") int batchSize);

    /**
     * 평가할 때가 된 여정을 한 번에 여러 건 골라 선점한다.
     *
     * <p>고르기와 선점을 한 문장으로 한다. 나누면 그 사이에 다른 인스턴스가 같은 여정을 골라
     * 같은 여정을 두 번 평가한다 — 외부 API 호출이 그만큼 낭비된다.
     *
     * <p><b>선점 방법은 다음 평가 시각을 미래로 미는 것이다.</b> 별도의 "처리 중" 칼럼을 두지
     * 않는다. 밀어 둔 시각이 지나도 결과가 안 들어오면(워커가 죽었으면) 다른 워커가 자연히 다시
     * 잡는다.
     *
     * <p>여기서 넘긴 {@code leaseUntil} 은 <b>소유권 증서</b>로도 쓰인다. 평가를 마친 워커는 저장
     * 전에 "이 여정의 다음 평가 시각이 아직 내가 심은 값인가"를 확인한다
     * ({@code finishEvaluation}). 늦게 끝난 워커가 최신 결과를 덮는 것을 그 조건이 막는다.
     *
     * <p>{@code idx_journey_due}(V35) 를 그대로 탄다 — 20,000행 기준 인덱스 스캔이고 정렬 단계가
     * 없다. 조건에 칼럼 연산을 넣으면 그 성질이 깨진다.
     *
     * <p>{@code @Modifying} 이 아니다({@code RETURNING} 이 결과를 돌려준다). 반드시
     * {@code @Transactional} 안에서 부른다.
     *
     * <p><b>선점 전 시각을 함께 돌려준다.</b> 이 UPDATE 가 {@code next_evaluation_at} 을 덮어쓰므로,
     * "이 여정이 언제부터 평가를 기다렸나"는 여기서 잡지 않으면 영영 알 수 없다. 선점 직후에 따로
     * SELECT 하는 방법은 다른 워커의 선점과 경합해 엉뚱한 값을 집는다.
     *
     * @return 이번에 선점한 여정 번호와 선점 전 다음 평가 시각
     */
    @Query(value = """
            WITH picked AS (
                SELECT s.id, s.next_evaluation_at AS due_at FROM journey_session s
                WHERE s.status = 'ACTIVE' AND s.deleted_at IS NULL
                  AND s.next_evaluation_at IS NOT NULL
                  AND s.next_evaluation_at <= :now
                ORDER BY s.next_evaluation_at
                LIMIT :batchSize
                FOR UPDATE OF s SKIP LOCKED)
            UPDATE journey_session j
               SET next_evaluation_at = :leaseUntil
            FROM picked
            WHERE j.id = picked.id
            RETURNING j.id AS id, picked.due_at AS due_at
            """, nativeQuery = true)
    List<DueClaimRow> claimDueBatch(@Param("now") Instant now,
                                    @Param("leaseUntil") Instant leaseUntil,
                                    @Param("batchSize") int batchSize);

    /** 선점 결과 한 줄. 칼럼 별칭으로 매핑된다. */
    interface DueClaimRow {
        Long getId();

        Instant getDueAt();
    }

    /**
     * 평가를 마친 여정의 다음 평가 시각을 기록한다. <b>소유권을 확인하는 자리이기도 하다.</b>
     *
     * <p>{@code next_evaluation_at = :claimedLeaseUntil} 조건이 핵심이다. claim 할 때 심어 둔 값이
     * 아직 그대로라면 이 여정은 여전히 내 것이다. 값이 바뀌었다면 lease 가 만료돼 다른 워커가
     * 가져갔거나, 단계 전이가 다음 평가를 앞당겼거나, 여정이 끝난 것이다. 그때는 0을 돌려주고
     * 부르는 쪽이 계산 결과를 버린다.
     *
     * <p>이 확인이 없으면 느렸던 워커가 낡은 판정을 최신 판정 위에 덮어쓴다.
     *
     * <p>{@code evaluationVersion} 은 실제로 값이 바뀌었을 때만 올린다. 조회 API 가 이 번호로
     * "바뀐 것 없음(204)"을 판단하므로, 상태·시각·근거가 그대로인데 번호를 올리면 앱이 30초마다
     * 같은 응답을 전부 다시 받는다.
     *
     * @param bump 판정 값이 직전과 달라졌는지
     * @return 1이면 내 소유가 확인됐고 기록됐다. 0이면 소유권을 잃었다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE journey_session
               SET next_evaluation_at = :nextEvaluationAt,
                   evaluation_version = evaluation_version + CASE WHEN :bump THEN 1 ELSE 0 END
             WHERE id = :id
               AND status = 'ACTIVE'
               AND deleted_at IS NULL
               AND next_evaluation_at = :claimedLeaseUntil
            """, nativeQuery = true)
    int finishEvaluation(@Param("id") Long id,
                         @Param("claimedLeaseUntil") Instant claimedLeaseUntil,
                         @Param("nextEvaluationAt") Instant nextEvaluationAt,
                         @Param("bump") boolean bump);

    /**
     * 탈퇴: 그 사용자의 여정을 소프트 삭제한다. 여정 기록은 남기되 조회·발송에서 빠지게 한다
     * (due 쿼리가 {@code deleted_at IS NULL} 을 본다). 물리 삭제하지 않는 이유는 ADR 0001 참조.
     */
    @Modifying(clearAutomatically = true)
    @Query("update JourneySession s set s.deletedAt = :now where s.userId = :userId and s.deletedAt is null")
    int softDeleteByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    // ── 재탐색 보드 갱신(#264, reroute-periodic-board PR-2) ──────────────────────

    /**
     * 갱신할 때가 된 여정을 선점한다. {@code claimDueBatch} 와 같은 방식이고, 선점은
     * {@code reroute_refresh_claimed_until} 로만 표시한다(V58) — 예정 시각과 섞지 않는다.
     *
     * <p>{@code @Modifying} 이 아니다({@code RETURNING}). 반드시 {@code @Transactional} 안에서 부른다.
     */
    @Query(value = """
            WITH picked AS (
                SELECT s.id FROM journey_session s
                WHERE s.status = 'ACTIVE' AND s.deleted_at IS NULL
                  AND s.reroute_refresh_at IS NOT NULL
                  AND s.reroute_refresh_at <= :now
                  AND (s.reroute_refresh_claimed_until IS NULL
                       OR s.reroute_refresh_claimed_until <= :now)
                ORDER BY s.reroute_refresh_at
                LIMIT :batchSize
                FOR UPDATE OF s SKIP LOCKED)
            UPDATE journey_session j
               SET reroute_refresh_claimed_until = :leaseUntil,
                   reroute_refresh_dirty = false
            FROM picked
            WHERE j.id = picked.id
            RETURNING j.id
            """, nativeQuery = true)
    List<Long> claimRerouteRefreshBatch(@Param("now") Instant now,
                                        @Param("leaseUntil") Instant leaseUntil,
                                        @Param("batchSize") int batchSize);

    /**
     * 갱신을 마치고 다음 예정을 심는다. <b>내가 잡은 선점일 때만</b> 쓴다.
     *
     * <p>선점 중에 신호가 왔으면({@code dirty}) 최소 간격 뒤로, 아니면 기본 주기 뒤로 잡는다. 조건 없이
     * 쓰면 그 사이 다른 워커가 다시 잡은 선점이나 들어온 신호를 덮는다.
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = """
            UPDATE journey_session
               SET reroute_refresh_claimed_until = NULL,
                   reroute_last_refresh_at = :now,
                   reroute_refresh_at = CASE WHEN reroute_refresh_dirty
                                             THEN CAST(:dirtyAt AS timestamptz)
                                             ELSE CAST(:nextAt AS timestamptz) END,
                   reroute_refresh_dirty = false
             WHERE id = :id AND reroute_refresh_claimed_until = :leaseUntil
            """, nativeQuery = true)
    int finishRerouteRefresh(@Param("id") Long id,
                             @Param("leaseUntil") Instant leaseUntil,
                             @Param("now") Instant now,
                             @Param("nextAt") Instant nextAt,
                             @Param("dirtyAt") Instant dirtyAt);

    /**
     * "지금 갱신해라" 신호(정류장 통과·구간 전이·수락·위험 하락).
     *
     * <p>선점 중이면 예정을 건드리지 않고 {@code dirty} 만 남긴다 — 앞당기면 다른 워커가 같은 여정을
     * 다시 잡는다. 선점 중이 아니면 예정을 "지금(최소 간격 존중)"으로 당긴다. 이미 더 이른 예정이 있으면
     * 그대로 둔다. 예정이 없던 여정(플래그를 켜기 전에 시작한 여정)은 여기서 처음 심긴다.
     *
     * @param bypassMinGap 위험 하락처럼 30초를 기다리면 안 되는 신호
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = """
            UPDATE journey_session
               SET reroute_refresh_dirty = CASE
                       WHEN reroute_refresh_claimed_until > CAST(:now AS timestamptz) THEN true
                       ELSE reroute_refresh_dirty END,
                   reroute_refresh_at = CASE
                       WHEN reroute_refresh_claimed_until > CAST(:now AS timestamptz)
                           THEN reroute_refresh_at
                       ELSE LEAST(COALESCE(reroute_refresh_at, CAST(:farFuture AS timestamptz)),
                                  CASE WHEN CAST(:bypassMinGap AS boolean)
                                            OR reroute_last_refresh_at IS NULL
                                           THEN CAST(:now AS timestamptz)
                                       ELSE GREATEST(CAST(:now AS timestamptz),
                                                     reroute_last_refresh_at
                                                     + (CAST(:minGapSeconds AS integer)
                                                        * interval '1 second')) END)
                       END
             WHERE id = :id AND status = 'ACTIVE' AND deleted_at IS NULL
            """, nativeQuery = true)
    int requestRerouteRefresh(@Param("id") Long id,
                              @Param("now") Instant now,
                              @Param("minGapSeconds") int minGapSeconds,
                              @Param("bypassMinGap") boolean bypassMinGap,
                              @Param("farFuture") Instant farFuture);

    /** 예정이 없을 때만 심는다(여정 시작). 이미 신호로 잡힌 예정을 늦추지 않는다. */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = """
            UPDATE journey_session SET reroute_refresh_at = :at
             WHERE id = :id AND reroute_refresh_at IS NULL
            """, nativeQuery = true)
    int scheduleRerouteRefreshIfAbsent(@Param("id") Long id, @Param("at") Instant at);

    /** 수락 시각을 남긴다. 수락 후 3분 동안 "새 기회" push 를 막는 기준이다(PR-5). */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = "UPDATE journey_session SET reroute_accepted_at = :at WHERE id = :id",
            nativeQuery = true)
    int markRerouteAccepted(@Param("id") Long id, @Param("at") Instant at);

    /**
     * 마지막 수락 시각(V57). 보드 push 의 "수락 후 3분" 판단에만 쓴다.
     *
     * <p>엔티티에 매핑하지 않는다 — 매핑하면 마이그레이션을 손으로 나열하는 IT 들이 전부 이 칸을 요구하게 된다.
     */
    @Query(value = "SELECT reroute_accepted_at FROM journey_session WHERE id = :id", nativeQuery = true)
    Optional<Instant> findRerouteAcceptedAt(@Param("id") Long id);
}
