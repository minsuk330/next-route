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
import java.util.Collection;
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
     * <p><b>선점 방법은 다음 평가 시각을 미래로 미는 것이다.</b> 밀어 둔 시각이 지나도 결과가 안
     * 들어오면(워커가 죽었으면) 다른 워커가 자연히 다시 잡는다. 같은 값을
     * {@code evaluation_claimed_until} 에도 적는다(V67) — 재평가 트리거가 "지금 처리 중인가"를
     * 보고, 처리 중이면 시각을 건드리지 않고 dirty 표시만 하게 하려는 것이다. 선점 때 dirty 를
     * 지우는 이유: 죽은 워커가 남긴 표시를 다음 선점이 물려받으면 같은 평가를 한 번 더 한다.
     *
     * <p>여기서 넘긴 {@code leaseUntil} 은 <b>소유권 증서</b>로도 쓰인다. 평가를 마친 워커는 저장
     * 전에 "이 여정의 다음 평가 시각이 아직 내가 심은 값인가"를 확인한다
     * ({@code finishEvaluation}). 늦게 끝난 워커가 최신 결과를 덮는 것을 그 조건이 막는다.
     *
     * <p>만기 행은 {@code idx_journey_due}(V35) 로 고른다. 조건에 칼럼 연산을 넣으면 그 성질이
     * 깨진다.
     *
     * <p><b>순서.</b> {@code urgentFirst} 면 세 줄로 나눈다: 급한 여정(환승 위험·임박, 시작·단계 전이
     * 직후) → 예정 시각에서 {@code starvedBefore} 보다 오래 밀린 여정 → 나머지. 각 줄 안에서는
     * 예정 시각 순이다. 평가기가 밀릴 때 2분 뒤 환승하는 여정이 40분 뒤 환승하는 여정 뒤에 서지 않게
     * 한다.
     *
     * <p>밀린 여정을 급한 줄과 <b>같은</b> 줄에 세우면 안 된다(측정: 계속 넘치는 동안 급하지 않은
     * 여정 대부분이 상한을 넘고, 줄 안에서 더 오래 기다린 쪽이 앞서 급한 여정이 다시 밀렸다 — 마감
     * 순서의 도미노와 같은 모양). 둘째 줄은 나머지보다만 앞서 굶지 않게 한다.
     * 순서를 바꾸면 만기 행 전체를 정렬하는 단계가 생긴다 — 정렬 대상은 적체 크기라 평상시 작다.
     * {@code urgentFirst} 가 false 면 예전과 같은 예정 시각 순(롤백 경로)이다.
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
                ORDER BY CASE WHEN NOT :urgentFirst THEN 0
                              WHEN s.evaluation_urgent THEN 0
                              WHEN s.next_evaluation_at <= :starvedBefore THEN 1
                              ELSE 2 END,
                         s.next_evaluation_at
                LIMIT :batchSize
                FOR UPDATE OF s SKIP LOCKED)
            UPDATE journey_session j
               SET next_evaluation_at = :leaseUntil,
                   evaluation_claimed_until = :leaseUntil,
                   evaluation_dirty = false
            FROM picked
            WHERE j.id = picked.id
            RETURNING j.id AS id, picked.due_at AS due_at
            """, nativeQuery = true)
    List<DueClaimRow> claimDueBatch(@Param("now") Instant now,
                                    @Param("leaseUntil") Instant leaseUntil,
                                    @Param("batchSize") int batchSize,
                                    @Param("urgentFirst") boolean urgentFirst,
                                    @Param("starvedBefore") Instant starvedBefore);

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
     * <p><b>처리 중에 데이터가 바뀌었으면(dirty)</b> 다음 평가를 {@code now + dirtyGap} 으로 당긴다.
     * 이번 계산은 바뀌기 전 값을 읽었을 수 있다. 다만 정책이 그보다 이른 시각을 정했으면 그쪽을 따른다
     * — 여유 등급의 간격(30초)이 위험 판정의 최소 간격(10초)을 뒤로 미루면 안 된다.
     *
     * @param bump 판정 값이 직전과 달라졌는지
     * @param urgent 다음 선점에서 먼저 잡을지(이번 판정이 환승 위험·임박)
     * @param now 평가를 마친 시각. {@code last_evaluated_at} 이 된다
     * @param dirtyGapSeconds dirty 였을 때 다음 평가까지 둘 최소 간격. 이번 평가의 등급에서 정한다
     * @return 1이면 내 소유가 확인됐고 기록됐다. 0이면 소유권을 잃었다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE journey_session
               SET next_evaluation_at = CASE WHEN evaluation_dirty
                       THEN LEAST(CAST(:nextEvaluationAt AS timestamptz),
                                  CAST(:now AS timestamptz)
                                      + make_interval(secs => CAST(:dirtyGapSeconds AS double precision)))
                       ELSE CAST(:nextEvaluationAt AS timestamptz) END,
                   evaluation_dirty = false,
                   evaluation_claimed_until = NULL,
                   last_evaluated_at = :now,
                   evaluation_urgent = :urgent,
                   evaluation_version = evaluation_version + CASE WHEN :bump THEN 1 ELSE 0 END
             WHERE id = :id
               AND status = 'ACTIVE'
               AND deleted_at IS NULL
               AND next_evaluation_at = :claimedLeaseUntil
            """, nativeQuery = true)
    int finishEvaluation(@Param("id") Long id,
                         @Param("claimedLeaseUntil") Instant claimedLeaseUntil,
                         @Param("nextEvaluationAt") Instant nextEvaluationAt,
                         @Param("bump") boolean bump,
                         @Param("urgent") boolean urgent,
                         @Param("now") Instant now,
                         @Param("dirtyGapSeconds") long dirtyGapSeconds);

    /**
     * 실시간 데이터가 바뀐 여정들의 다음 평가를 앞당긴다(#415-3 재평가 트리거).
     *
     * <p><b>처리 중이면 표시만 한다.</b> 평가기가 지금 그 여정을 계산하고 있으면
     * ({@code evaluation_claimed_until > now}) 다음 평가 시각(= 선점 증서)을 건드리지 않고 dirty 만
     * 세운다. 증서를 덮으면 진행 중 계산이 소유권을 잃어 결과를 버리고, 이벤트가 계산 시간보다 잦은
     * 여정은 매번 버려져 영영 저장되지 않는다(livelock). dirty 는 평가를 마칠 때
     * {@link #finishEvaluation} 이 읽어 곧바로 다음 평가를 잡는다.
     *
     * <p>다음 평가 시각이 비어 있는 행은 건드리지 않는다. {@code LEAST(NULL, x)} 는 x 라 예정이 없던
     * 행에 새 예정을 만들어 버린다.
     *
     * <p><b>처리 중이 아니면 당기기만 한다.</b> {@code LEAST} 라 이미 더 이른 예정은 그대로 두고,
     * 직전 평가로부터 {@code minGapSeconds} 는 지키게 한다 — 이벤트가 몰려도 여정당 평가 횟수에
     * 하한 간격이 생긴다. lease 가 만료된 채 남은 행은 {@code LEAST(지난 lease, …)} 가 지난 lease 를
     * 고르므로 증서가 보존된다.
     *
     * <p><b>잠금 순서를 id 로 고정한다.</b> 여러 행을 한 문장으로 고치면 계획 순서대로 잠그는데, 두
     * 트리거 묶음이 겹치는 여정을 서로 다른 순서로 잡으면 교착한다. CTE 에서 id 순으로 먼저 잠근다.
     * 선점({@code FOR UPDATE SKIP LOCKED})이 잡고 있는 행은 기다렸다가 커밋 뒤의 새 값으로 다시 판단하므로
     * "처리 중"을 놓치지 않는다.
     *
     * <p>{@code @Modifying} 이 아니다({@code RETURNING}). 반드시 {@code @Transactional} 안에서 부른다.
     *
     * @return 바뀐 여정마다 처리 중이라 표시만 했는지
     */
    @Query(value = """
            WITH t AS (
                SELECT id, COALESCE(evaluation_claimed_until > :now, false) AS claimed
                  FROM journey_session
                 WHERE id IN (:ids)
                   AND status = 'ACTIVE' AND deleted_at IS NULL AND kind = 'NAVIGATION'
                   AND next_evaluation_at IS NOT NULL
                 ORDER BY id
                 FOR UPDATE)
            UPDATE journey_session j
               SET evaluation_dirty = j.evaluation_dirty OR t.claimed,
                   next_evaluation_at = CASE WHEN t.claimed THEN j.next_evaluation_at
                       ELSE LEAST(j.next_evaluation_at,
                                  GREATEST(CAST(:now AS timestamptz),
                                           j.last_evaluated_at
                                               + make_interval(secs => CAST(:minGapSeconds AS double precision))))
                       END
              FROM t
             WHERE j.id = t.id
            RETURNING j.id AS id, t.claimed AS claimed
            """, nativeQuery = true)
    List<ReevaluationRow> requestReevaluation(@Param("ids") Collection<Long> ids,
                                              @Param("now") Instant now,
                                              @Param("minGapSeconds") long minGapSeconds);

    /**
     * 진행 중 길안내 여정이 지금 지켜봐야 할 실시간 키(#415-3 관심 키 색인).
     *
     * <p>노선은 평가기가 읽는 칸과 같게 고른다 — 지하철은 구간의 노선({@code WaitSubwayArrivalPredictor}),
     * 버스는 고른 선택지의 노선({@code BusTransferTargetArrivalPredictor}).
     *
     * <p>남은 대기 구간(버스·지하철) 하나가 한 줄이다. 지난 구간은 빼고, 현재 개정본만 본다. 남은 환승
     * 중 가장 가까운 준비 시각과 위험 여부를 함께 실어 색인이 등급을 메모리에서 정하게 한다.
     *
     * <p>활성 행에서 출발한다(V67 {@code idx_journey_session_active_nav}). 구간·선택지는
     * {@code uq_journey_leg_index}·PK 로 붙는다. 정류장·노선 ID 인덱스는 두지 않는다 — 역방향 찾기는
     * 메모리 색인이 한다.
     */
    @Query(value = """
            SELECT s.id AS journeyId,
                   l.type AS legType,
                   CASE WHEN l.type = 'WAIT_SUBWAY' THEN l.route_id
                        ELSE COALESCE(o.route_id, l.route_id) END AS routeId,
                   l.start_stop_id AS stopId,
                   l.start_name AS stopName,
                   o.boarding_seq AS boardingSeq,
                   o.way_code AS wayCode,
                   (SELECT min(t.user_ready_at) FROM transfer_connection t
                     WHERE t.journey_id = s.id AND t.route_revision = s.route_revision
                       AND t.to_leg_index >= s.current_leg_index) AS nextReadyAt,
                   EXISTS (SELECT 1 FROM transfer_connection t
                            WHERE t.journey_id = s.id AND t.route_revision = s.route_revision
                              AND t.to_leg_index >= s.current_leg_index
                              AND t.status IN ('AT_RISK', 'MISSED')) AS risky
              FROM journey_session s
              JOIN journey_leg l
                ON l.journey_id = s.id AND l.route_revision = s.route_revision
               AND l.leg_index >= s.current_leg_index
               AND l.type IN ('WAIT_BUS', 'WAIT_SUBWAY')
              LEFT JOIN journey_leg_route_option o ON o.id = l.selected_route_option_id
             WHERE s.status = 'ACTIVE' AND s.deleted_at IS NULL AND s.kind = 'NAVIGATION'
            """, nativeQuery = true)
    List<InterestRow> findInterestRows();

    /** 관심 키 한 줄. */
    interface InterestRow {
        Long getJourneyId();

        String getLegType();

        String getRouteId();

        String getStopId();

        String getStopName();

        Integer getBoardingSeq();

        Integer getWayCode();

        Instant getNextReadyAt();

        Boolean getRisky();
    }

    /**
     * 조건부 조회(204)용 버전만 읽는다(#415-6). 여정 행과 지금 단계의 종류만 — 단계·노선 후보 전체를 읽지 않는다.
     *
     * <p>앱은 10초마다 조회하고 대부분은 "바뀐 것 없음"이다. 그때마다 여정·단계·후보 세 번을 읽으면 동시
     * 여정 6,000 에서 조회가 커넥션 풀을 차지해 평가기까지 밀린다(부하 사다리 실측). 버전이 같으면 이 한 번으로
     * 끝낸다. 소유자 조건은 그대로 쿼리에 넣는다.
     */
    @Query(value = """
            SELECT s.command_version AS commandVersion,
                   s.evaluation_version AS evaluationVersion,
                   l.type AS currentLegType
              FROM journey_session s
              LEFT JOIN journey_leg l
                ON l.journey_id = s.id AND l.route_revision = s.route_revision
               AND l.leg_index = s.current_leg_index AND l.deleted_at IS NULL
             WHERE s.id = :id AND s.user_id = :userId AND s.deleted_at IS NULL
            """, nativeQuery = true)
    Optional<VersionRow> findVersions(@Param("id") Long id, @Param("userId") Long userId);

    /** 조건부 조회용 버전 한 줄. */
    interface VersionRow {
        Long getCommandVersion();

        Long getEvaluationVersion();

        String getCurrentLegType();
    }

    /** 재평가 트리거 결과 한 줄. */
    interface ReevaluationRow {
        Long getId();

        Boolean getClaimed();
    }

    /**
     * 예정 시각에서 {@code before} 보다 더 밀린 진행 중 여정 수. 평가기가 포화됐는지 보는 신호다.
     *
     * <p>여정 종류(길안내·단독 추적)를 가리지 않는다 — 평가기가 둘 다 선점하므로 둘 다 평가기의 일이다.
     *
     * <p>{@code idx_journey_due}(V35) 범위 스캔이라 적체가 작을 때 싸다. 적체가 클 때만 비싸지는데,
     * 그때는 트리거가 덜 급한 등급을 건너뛰는 근거가 되므로 정확히 셀 가치가 있다.
     */
    @Query(value = """
            SELECT count(*) FROM journey_session
             WHERE status = 'ACTIVE' AND deleted_at IS NULL
               AND next_evaluation_at IS NOT NULL
               AND next_evaluation_at <= :before
            """, nativeQuery = true)
    long countOverdueSince(@Param("before") Instant before);

    /**
     * 탈퇴: 그 사용자의 여정을 소프트 삭제한다. 여정 기록은 남기되 조회·발송에서 빠지게 한다
     * (due 쿼리가 {@code deleted_at IS NULL} 을 본다). 물리 삭제하지 않는 이유는 ADR 0001 참조.
     */
    @Modifying(clearAutomatically = true)
    @Query("update JourneySession s set s.deletedAt = :now where s.userId = :userId and s.deletedAt is null")
    int softDeleteByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    // ── 재탐색 보드 갱신(#264, reroute-periodic-board PR-2) ──────────────────────
    // 단독 추적(kind = 'TRACK', #265)은 재탐색이 없다. 선점·예약·수락 기록 SQL 이 모두 kind 를 걸러,
    // 세션을 읽은 뒤 거르는 경쟁 창 없이 보드가 아예 생기지 않게 한다.

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
                  AND s.kind = 'NAVIGATION'
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
               AND kind = 'NAVIGATION'
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
             WHERE id = :id AND reroute_refresh_at IS NULL AND kind = 'NAVIGATION'
            """, nativeQuery = true)
    int scheduleRerouteRefreshIfAbsent(@Param("id") Long id, @Param("at") Instant at);

    /** 수락 시각을 남긴다. 수락 후 3분 동안 "새 기회" push 를 막는 기준이다(PR-5). */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = "UPDATE journey_session SET reroute_accepted_at = :at WHERE id = :id AND kind = 'NAVIGATION'",
            nativeQuery = true)
    int markRerouteAccepted(@Param("id") Long id, @Param("at") Instant at);

    /**
     * 마지막 수락 시각(V57). 보드 push 의 "수락 후 3분" 판단에만 쓴다.
     *
     * <p>엔티티에 매핑하지 않는다 — 매핑하면 마이그레이션을 손으로 나열하는 IT 들이 전부 이 칸을 요구하게 된다.
     */
    @Query(value = "SELECT reroute_accepted_at FROM journey_session WHERE id = :id", nativeQuery = true)
    Optional<Instant> findRerouteAcceptedAt(@Param("id") Long id);

    /**
     * 지하철 탑승 중 오프라인 보호를 <b>늘리기만</b> 한다. 여정이 아직 그 탑승 구간일 때만.
     *
     * <p>열차를 고르면 곧바로 탑승으로 넘어가서 탑승 시작 시각이 승강장 시각이 된다. 계획 소요시간 기준 보호는 기다린
     * 시간만큼 모자라 지하에서 만료된다. 링 갱신기가 하차 도착 예정으로 이 칸을 밀어 준다. 줄이지 않으므로 명령 경로가 정한
     * 값과 다투지 않는다. 행 잠금 없이 한 문장 — 5초 tick 이 사용자 요청을 기다리지 않게.
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("""
            update JourneySession j
               set j.subwayOfflineProtectionUntil = :until
             where j.id = :id and j.status = watoo.grd.nextroute.domain.journey.entity.JourneyStatus.ACTIVE
               and j.deletedAt is null
               and j.routeRevision = :routeRevision
               and j.currentLegIndex = :legIndex
               and (j.subwayOfflineProtectionUntil is null or j.subwayOfflineProtectionUntil < :until)
            """)
    int extendSubwayProtection(@Param("id") Long id,
                               @Param("routeRevision") int routeRevision,
                               @Param("legIndex") int legIndex,
                               @Param("until") Instant until);

    // ── 울릴 권리(#428 PR-1b, V69) ───────────────────────────────────────────────
    //
    // 칸 두 개(alert_claim_revision, alert_claim_key)는 엔티티에 매핑하지 않는다. 여정 엔티티를 통째로
    // 저장하는 경로가 옛 값을 되써 권리를 되살리거나 지우면 안 된다. 그래서 전부 native 다.

    /**
     * 위험·재탐색 알림을 쓰기 전에 여정 행을 먼저 잠근다.
     *
     * <p>잠금 순서를 모든 쓰기 경로에서 journey_session → reroute_proposal → journey_notification 으로 맞추려는
     * 것이다. 평가기는 {@link #finishEvaluation} 이 이미 이 행을 잠그고 시작하므로, 보드 push·재탐색 알림도
     * 같은 행부터 잡아야 서로 반대 순서로 기다리는 일이 없다. 잠금이 곧 "권리 판정을 한 줄로 세우기"이기도 하다.
     */
    @Query(value = "SELECT id FROM journey_session WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Long> lockForAlert(@Param("id") Long id);

    /**
     * 이 경로 버전의 울릴 권리를 따낸다. 아직 아무도 못 땄을 때만 1행.
     *
     * <p>{@code IS DISTINCT FROM} 이라 칸이 비었거나(처음·돌려받은 뒤) 옛 경로 버전이면 이긴다.
     *
     * @return 1 이면 이 알림이 울린다. 0 이면 이미 누가 땄다
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = """
            UPDATE journey_session
               SET alert_claim_revision = :rev, alert_claim_key = :key
             WHERE id = :id AND alert_claim_revision IS DISTINCT FROM :rev
            """, nativeQuery = true)
    int tryClaimAlert(@Param("id") Long id, @Param("rev") int rev, @Param("key") String key);

    /**
     * 물려받기. 권리를 가진 알림을 이번 트랜잭션이 아직 안 나간 채로 취소했으면 권리를 새 알림으로 옮긴다.
     *
     * <p>그 논리 키의 다른 채널 행이 이미 발송 중이거나 나갔으면 옮기지 않는다 — 사용자는 이미 진동을 받았고,
     * 옮기면 같은 경로 버전에서 두 번 울린다.
     *
     * @param fromKeys 이번 트랜잭션이 실제로 PENDING → CANCELED 로 옮긴 행의 논리 키. 비면 부르지 않는다
     * @return 1 이면 물려받았다
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = """
            UPDATE journey_session s
               SET alert_claim_key = :toKey
             WHERE s.id = :id AND s.alert_claim_revision = :rev
               AND s.alert_claim_key IN (:fromKeys)
               AND NOT EXISTS (SELECT 1 FROM journey_notification n
                                WHERE n.journey_id = :id
                                  AND n.logical_checkpoint_key = s.alert_claim_key
                                  AND n.status IN ('PROCESSING', 'SENT'))
            """, nativeQuery = true)
    int inheritAlertClaim(@Param("id") Long id, @Param("rev") int rev,
                          @Param("fromKeys") Collection<String> fromKeys, @Param("toKey") String toKey);

    /**
     * 돌려받기. 권리를 가진 알림이 실제로 안 나갔으면 비워 다음 악화가 울리게 한다.
     *
     * <p>같은 논리 키의 다른 채널 행이 아직 살아 있거나(PENDING·PROCESSING) 이미 나갔으면(SENT) 비우지 않는다 —
     * 한 채널만 실패했을 뿐 사용자는 울림을 받는다(또는 받을 것이다).
     *
     * @param keys            비울 후보 논리 키. 지금 권리를 가진 키와 같을 때만 실제로 비운다
     * @param exceptNotificationId 지금 끝내는 행. 상태 변경이 아직 반영되기 전이라 살아 있는 행으로 세지 않게 뺀다.
     *                        해당 없으면 0
     * @return 1 이면 비웠다
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query(value = """
            UPDATE journey_session s
               SET alert_claim_revision = NULL, alert_claim_key = NULL
             WHERE s.id = :id AND s.alert_claim_key IN (:keys)
               AND NOT EXISTS (SELECT 1 FROM journey_notification n
                                WHERE n.journey_id = :id
                                  AND n.logical_checkpoint_key = s.alert_claim_key
                                  AND n.id <> :exceptNotificationId
                                  AND n.status IN ('PENDING', 'PROCESSING', 'SENT'))
            """, nativeQuery = true)
    int releaseAlertClaim(@Param("id") Long id, @Param("keys") Collection<String> keys,
                          @Param("exceptNotificationId") long exceptNotificationId);

    /** 이 경로 버전의 권리를 가진 알림의 논리 키. 아무도 안 땄거나 옛 경로 버전이면 빈 값. */
    @Query(value = """
            SELECT alert_claim_key FROM journey_session
             WHERE id = :id AND alert_claim_revision = :rev
            """, nativeQuery = true)
    Optional<String> findAlertClaimKey(@Param("id") Long id, @Param("rev") int rev);
}
