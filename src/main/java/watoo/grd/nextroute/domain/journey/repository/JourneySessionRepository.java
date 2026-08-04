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
               and j.deletedAt is null
            """)
    int bumpEvaluationVersion(@Param("id") Long id);

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
     * @return 이번에 선점한 여정 번호
     */
    @Query(value = """
            WITH picked AS (
                SELECT s.id FROM journey_session s
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
            RETURNING j.id
            """, nativeQuery = true)
    List<Long> claimDueBatch(@Param("now") Instant now,
                             @Param("leaseUntil") Instant leaseUntil,
                             @Param("batchSize") int batchSize);

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
}
