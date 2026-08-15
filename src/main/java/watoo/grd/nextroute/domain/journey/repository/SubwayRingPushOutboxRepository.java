package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.journey.entity.SubwayRingPushOutbox;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 진행 링 push 대기열.
 *
 * <h2>왜 대부분 네이티브 문장인가</h2>
 *
 * <p>이 표가 막으려는 사고는 전부 <b>읽고 고치는 사이에 벌어지는 일</b>이다 — 순번을 읽는 사이에
 * 새 행이 들어오고, 최신성을 확인한 사이에 더 새 것이 생긴다. 엔티티를 읽어 고치는 방식으로는
 * 그 창이 반드시 열리므로, 채번·최신성 판정·상태 전이를 <b>조건부 UPDATE 한 문장</b>으로 닫는다.
 */
public interface SubwayRingPushOutboxRepository extends JpaRepository<SubwayRingPushOutbox, Long> {

    // ── 넣기 ────────────────────────────────────────────────────────────────

    /**
     * 그 키의 <b>가장 최근</b> 행의 signature. 같으면 화면이 그대로라는 뜻이라 넣지 않는다.
     *
     * <p>상태를 가리지 않는다. {@code SENT} 든 {@code SKIPPED} 든 "그 모양을 이미 만들어 봤다"는
     * 사실은 같고, 링은 5초마다 도는데 대부분의 tick 은 화면을 바꾸지 않는다.
     */
    @Query(value = """
            SELECT o.signature FROM subway_ring_push_outbox o
             WHERE o.journey_id = :journeyId
               AND o.route_revision = :routeRevision
               AND o.leg_index = :legIndex
             ORDER BY o.sequence DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<String> findLatestSignature(@Param("journeyId") Long journeyId,
                                         @Param("routeRevision") int routeRevision,
                                         @Param("legIndex") int legIndex);

    /**
     * 그 여정에 <b>마지막으로 만든</b> 행.
     *
     * <p>종료 프레임이 쓴다. 종료는 "마지막으로 보여 준 화면에 시각만 새로 찍은 것"이어야
     * 하는데, 그 화면이 여기 들어 있다. 이 행이 없다는 것은 그 여정에 아무것도 보낸 적이
     * 없다는 뜻이므로 <b>지울 화면도 없다</b>.
     */
    Optional<SubwayRingPushOutbox> findFirstByJourneyIdOrderBySequenceDesc(Long journeyId);

    /**
     * 그 키의 대기 중인 행을 폐기한다. 새 것을 넣기 <b>직전에</b> 부른다.
     *
     * <p>부분 unique 가 있어 이걸 건너뛰면 INSERT 가 충돌한다. 그리고 충돌하지 않더라도, 대기
     * 중인 옛 화면을 그대로 두면 순서가 뒤집혔을 때 그것이 새 화면을 덮는다.
     *
     * @return 폐기한 행 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = """
            UPDATE subway_ring_push_outbox
               SET status = 'SKIPPED', last_failure_reason = 'superseded', updated_at = :now
             WHERE journey_id = :journeyId
               AND route_revision = :routeRevision
               AND leg_index = :legIndex
               AND status = 'PENDING'
            """, nativeQuery = true)
    int skipOpen(@Param("journeyId") Long journeyId,
                 @Param("routeRevision") int routeRevision,
                 @Param("legIndex") int legIndex,
                 @Param("now") LocalDateTime now);

    /**
     * 한 건을 넣는다. <b>순번과 timestamp 를 같은 문장 안에서 채번한다.</b>
     *
     * <p>둘 다 여정 전체에서 단조 증가해야 한다. 밖에서 읽어 계산하면 두 워커가 같은 번호를 쓰고,
     * 그 둘의 선후는 영영 가릴 수 없게 된다.
     *
     * <p>{@code aps_timestamp} 를 {@code GREATEST(지금, 직전+1)} 로 잡는 이유. 실제 시각에 붙여
     * 두어야 APNs 가 다른 알림들과 견줄 수 있는데, 같은 초 안에 두 건이 나가면 값이 같아져 순서가
     * 사라진다. 직전보다 반드시 하나 크게 만들면 <b>단조성과 실시간성을 함께</b> 지킨다.
     *
     * <p>{@code ON CONFLICT DO NOTHING} 인 이유. 두 워커가 동시에 같은 여정을 계산하면 한쪽이
     * 순번 unique 나 대기 unique 에 걸린다. 그것은 <b>누가 더 새 걸 넣었다</b>는 뜻이므로 예외가
     * 아니라 정상 결말이다 — 여기서 던지면 그 tick 전체가 죽는다.
     *
     * @return 1 = 넣었다, 0 = 누가 먼저 넣었다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = """
            INSERT INTO subway_ring_push_outbox
                (journey_id, route_revision, leg_index, binding_version, sequence, aps_timestamp,
                 signature, critical, reason, event, payload, status, attempt_count,
                 expires_at, created_at, updated_at)
            SELECT :journeyId, :routeRevision, :legIndex, :bindingVersion,
                   COALESCE(MAX(o.sequence), 0) + 1,
                   GREATEST(:nowEpochSeconds, COALESCE(MAX(o.aps_timestamp), 0) + 1),
                   :signature, :critical, :reason, :event, cast(:payload as jsonb), 'PENDING', 0,
                   :expiresAt, :now, :now
              FROM subway_ring_push_outbox o
             WHERE o.journey_id = :journeyId
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int enqueue(@Param("journeyId") Long journeyId,
                @Param("routeRevision") int routeRevision,
                @Param("legIndex") int legIndex,
                @Param("bindingVersion") int bindingVersion,
                @Param("nowEpochSeconds") long nowEpochSeconds,
                @Param("signature") String signature,
                @Param("critical") boolean critical,
                @Param("reason") String reason,
                @Param("event") String event,
                @Param("payload") String payload,
                @Param("expiresAt") Instant expiresAt,
                @Param("now") LocalDateTime now);

    // ── 집어 가기 ───────────────────────────────────────────────────────────

    /**
     * relay 배치 claim: {@code PENDING → SENDING}({@code SKIP LOCKED}).
     *
     * <p>{@code attempt_count} 를 여기서 올린다 — 발송 직전 재확인이 취소한 행도 시도를 하나
     * 쓴 것으로 세야 무한히 되돌아오지 않는다.
     *
     * <p><b>이 순간 그 행은 대기 unique 의 술어에서 빠진다.</b> 그래서 갱신 tick 이 새
     * {@code PENDING} 을 넣을 수 있고, 그 창은 발송 직전 재확인이 막는다.
     *
     * <p>{@code @Modifying} 이 아니다(RETURNING). {@code @Transactional} 필수.
     */
    @Query(value = """
            UPDATE subway_ring_push_outbox
               SET status = 'SENDING', sending_at = :now,
                   attempt_count = attempt_count + 1, updated_at = :auditNow
             WHERE id IN (SELECT o.id FROM subway_ring_push_outbox o
                           WHERE o.status = 'PENDING'
                           ORDER BY o.created_at
                           LIMIT :batchSize
                           FOR UPDATE SKIP LOCKED)
            RETURNING id
            """, nativeQuery = true)
    List<Long> claimBatch(@Param("now") Instant now,
                          @Param("batchSize") int batchSize,
                          @Param("auditNow") LocalDateTime auditNow);

    /** 그 키에서 지금까지 만들어진 가장 큰 순번. 발송 직전 재확인이 쓴다. */
    @Query(value = """
            SELECT COALESCE(MAX(o.sequence), 0) FROM subway_ring_push_outbox o
             WHERE o.journey_id = :journeyId
               AND o.route_revision = :routeRevision
               AND o.leg_index = :legIndex
            """, nativeQuery = true)
    long findMaxSequence(@Param("journeyId") Long journeyId,
                         @Param("routeRevision") int routeRevision,
                         @Param("legIndex") int legIndex);

    // ── 결말 ────────────────────────────────────────────────────────────────

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = """
            UPDATE subway_ring_push_outbox
               SET status = 'SENT', sent_at = :sentAt, updated_at = :now
             WHERE id = :id AND status = 'SENDING'
            """, nativeQuery = true)
    int markSent(@Param("id") Long id,
                 @Param("sentAt") Instant sentAt,
                 @Param("now") LocalDateTime now);

    /** 발송하지 않고 접는다. 구형이거나 세대가 어긋났을 때. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = """
            UPDATE subway_ring_push_outbox
               SET status = 'SKIPPED', last_failure_reason = :reason, updated_at = :now
             WHERE id = :id AND status IN ('SENDING', 'PENDING')
            """, nativeQuery = true)
    int markSkipped(@Param("id") Long id,
                    @Param("reason") String reason,
                    @Param("now") LocalDateTime now);

    /**
     * 실패를 <b>원자적으로</b> 판정한다. 되돌릴지 접을지를 같은 문장에서 정한다.
     *
     * <p>읽고 나서 정하면 그 사이에 더 새 것이 생겨, 이미 구형이 된 행을 대기열에 되돌리게 된다.
     *
     * <ul>
     *   <li>더 새 순번이 있다 → {@code SKIPPED}. 실패가 아니라 <b>구형</b>이다</li>
     *   <li>유효기간이 지났다 / 시도를 다 썼다 → {@code FAILED}</li>
     *   <li>그 밖 → {@code PENDING} 으로 되돌린다(최신인 동안의 재시도)</li>
     * </ul>
     *
     * <p>{@code PENDING} 으로 되돌릴 때 대기 unique 와 충돌할 수 있는지: 더 새 {@code PENDING} 이
     * 있으면 그 순번이 더 크므로 첫 갈래가 잡는다. 그래도 동시 삽입과 겹치는 좁은 창이 남아,
     * 부르는 쪽이 충돌을 {@code SKIPPED} 로 흡수한다.
     *
     * @return 1 = 판정했다, 0 = 그 사이 누가 상태를 바꿨다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = """
            UPDATE subway_ring_push_outbox o
               SET status = CASE
                     WHEN o.sequence < (SELECT MAX(x.sequence) FROM subway_ring_push_outbox x
                                         WHERE x.journey_id = o.journey_id
                                           AND x.route_revision = o.route_revision
                                           AND x.leg_index = o.leg_index) THEN 'SKIPPED'
                     WHEN o.expires_at <= :now THEN 'FAILED'
                     WHEN o.attempt_count >= :maxAttempts THEN 'FAILED'
                     ELSE 'PENDING'
                   END,
                   last_failure_reason = :reason,
                   sending_at = NULL,
                   updated_at = :auditNow
             WHERE o.id = :id AND o.status = 'SENDING'
            """, nativeQuery = true)
    int resolveFailure(@Param("id") Long id,
                       @Param("reason") String reason,
                       @Param("maxAttempts") int maxAttempts,
                       @Param("now") Instant now,
                       @Param("auditNow") LocalDateTime auditNow);

    // ── 그물 ────────────────────────────────────────────────────────────────

    /**
     * 고착 {@code SENDING} 회수. 워커가 죽으면 그 행은 아무도 건드리지 않는다.
     *
     * <p>{@code attempt_count} 는 claim 이 이미 올렸으므로 여기서 또 올리지 않는다 — 두 번 세면
     * 재시도 예산이 절반으로 줄어 종료 push 가 유효기간 안에 못 나간다.
     *
     * @return 회수한 행 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE subway_ring_push_outbox
               SET status = 'PENDING', sending_at = NULL, updated_at = :now
             WHERE status = 'SENDING' AND sending_at < :staleBefore
            """, nativeQuery = true)
    int reclaimStale(@Param("staleBefore") Instant staleBefore, @Param("now") LocalDateTime now);

    /**
     * 유효기간이 지난 대기 행을 접는다.
     *
     * <p>늦게 뜬 진행률은 거짓말이다. 다만 <b>종료</b>는 늦더라도 나가는 편이 낫다 — 잠금화면에
     * 죽은 안내가 남는 것보다는 낫기 때문이다. 그래서 {@code event = 'update'} 만 접는다.
     *
     * @return 접은 행 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE subway_ring_push_outbox
               SET status = 'FAILED', last_failure_reason = 'expired', updated_at = :now
             WHERE status = 'PENDING' AND event = 'update' AND expires_at <= :expiredBefore
            """, nativeQuery = true)
    int expireStale(@Param("expiredBefore") Instant expiredBefore,
                    @Param("now") LocalDateTime now);
}
