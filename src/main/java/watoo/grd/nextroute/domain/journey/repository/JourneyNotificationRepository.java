package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotification;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 여정 알림 outbox 접근. 상태 기계는 {@code BusAlertNotificationRepository} 를 따르되 시각이
 * {@link Instant}(timestamptz)다.
 *
 * <p>시각 파라미터가 두 종류인 점을 조심할 것 — {@code now}·{@code expiresAt} 은 도메인 시각
 * ({@link Instant}, timestamptz)이고 {@code auditNow} 는 {@code BaseEntity} 의 감사 칼럼
 * ({@link LocalDateTime}, {@code timestamp(6)})이다. 이름을 헷갈리면 조용히 어긋난다.
 */
public interface JourneyNotificationRepository extends JpaRepository<JourneyNotification, Long> {

    /**
     * enqueue. 여정 재확인 CTE + INSERT 를 한 문장으로 묶는다.
     *
     * <p>CTE 가 여정이 아직 ACTIVE 이고 {@code route_revision} 이 계산 시점과 같은지 확인한다.
     * 여정이 끝났거나 경로가 바뀌었으면 CTE 가 비어 0행 삽입 — 예외 없이.
     *
     * <p>{@code ON CONFLICT} 대상은 {@code delivery_key} 다. {@code logical_checkpoint_key} 로
     * 걸면 두 번째 채널이 조용히 사라진다 — 키가 두 겹인 이유가 그것이다.
     *
     * <p>두 version 은 파라미터로 받지 않고 CTE 가 {@code journey_session} 에서 그대로 집어 온다.
     * 같은 트랜잭션 안이라 CAS({@code finishEvaluation})가 {@code evaluation_version} 을 올린
     * <b>뒤</b>의 값을 본다 — Java 로 옮기려면 native UPDATE 가 올린 값을 다시 읽어야 해서
     * 쿼리가 하나 더 는다.
     *
     * <p>{@code @Modifying} 이 아니다(RETURNING). 반드시 {@code @Transactional} 안에서 부른다.
     *
     * @param priority 발송 우선순위({@code NotificationPriority#value()}, #451 PR-2). 호출부마다 손으로 숫자를 쓰지 말 것
     * @return 생성된 행 id(1건). 중복이거나 여정이 끝났으면 0건
     */
    @Query(value = """
            WITH src AS (
                SELECT s.id AS journey_id, s.user_id,
                       s.command_version, s.evaluation_version
                FROM journey_session s
                WHERE s.id = :journeyId
                  AND s.route_revision = :routeRevision
                  AND s.status = 'ACTIVE'
                  AND s.deleted_at IS NULL)
            INSERT INTO journey_notification
                (journey_id, user_id, route_revision, command_version, evaluation_version,
                 scope_type, scope_id, checkpoint, channel,
                 logical_checkpoint_key, delivery_key, status, scheduled_at, expires_at, payload,
                 attempt_count, requeue_count, priority, created_at, updated_at)
            SELECT src.journey_id, src.user_id, :routeRevision,
                   src.command_version, src.evaluation_version,
                   :scopeType, :scopeId, :checkpoint, :channel,
                   :logicalKey, :deliveryKey, 'PENDING', :now, :expiresAt,
                   cast(:payload as jsonb), 0, 0, :priority, :auditNow, :auditNow
            FROM src
            ON CONFLICT (delivery_key) DO NOTHING
            RETURNING id
            """, nativeQuery = true)
    List<Long> enqueue(@Param("journeyId") Long journeyId, @Param("routeRevision") int routeRevision,
                       @Param("scopeType") String scopeType, @Param("scopeId") int scopeId,
                       @Param("checkpoint") String checkpoint, @Param("channel") String channel,
                       @Param("logicalKey") String logicalKey, @Param("deliveryKey") String deliveryKey,
                       @Param("now") Instant now, @Param("expiresAt") Instant expiresAt,
                       @Param("payload") String payload, @Param("priority") short priority,
                       @Param("auditNow") LocalDateTime auditNow);

    /**
     * 같은 대상의 낡은 PENDING 을 밀어낸다.
     *
     * <p>{@code AT_RISK} 와 {@code MISSED} 는 별개 행이라 재시도로 순서가 뒤집힐 수 있다 —
     * 사용자가 "놓쳤다" 다음에 "놓칠 것 같다"를 본다. 상태가 좋아졌을 때 쌓여 있던 알림이 최대
     * 30분 살아 있는 것도 같은 문제다.
     *
     * <p>방금 만든 행들({@code exceptDeliveryKeys})만 남긴다. 이 목록은 <b>비면 안 된다</b> —
     * 빈 목록으로 부르면 방금 만든 것까지 취소한다. 그 대상의 PENDING 을 전부 취소하는 회복
     * 경로는 {@link #supersedeAllPendingByScope} 다.
     *
     * <p>PROCESSING 은 건드리지 않는다 — 이미 발송 중이라
     * {@code JourneyNotificationOutboxStateService#loadTask} 의 재확인이 안전망이다.
     *
     * <p>{@code clearAutomatically = false} 다. 부르는 쪽(알림 판단)이 같은 트랜잭션에서
     * {@code TransferConnection} 마커를 고치고 있어 영속성 컨텍스트를 비우면 그 변경이 사라진다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("""
            update JourneyNotification n
               set n.status = watoo.grd.nextroute.domain.journey.entity.JourneyNotificationStatus.CANCELED,
                   n.lastFailureReason = 'superseded'
             where n.journeyId = :journeyId
               and n.routeRevision = :routeRevision
               and n.scopeType = :scopeType
               and n.scopeId = :scopeId
               and n.status = watoo.grd.nextroute.domain.journey.entity.JourneyNotificationStatus.PENDING
               and n.deliveryKey not in :exceptDeliveryKeys
            """)
    int supersedePendingByScope(@Param("journeyId") Long journeyId,
                                @Param("routeRevision") int routeRevision,
                                @Param("scopeType") JourneyNotificationScope scopeType,
                                @Param("scopeId") int scopeId,
                                @Param("exceptDeliveryKeys") Collection<String> exceptDeliveryKeys);

    /**
     * 회복 경로. 그 대상의 <b>위험 알림</b> PENDING 을 취소한다.
     *
     * <p>환승 상태가 알릴 값이 아닌 값으로 좋아졌을 때 부른다 — 안 하면 옛 {@code AT_RISK} 가
     * 최대 30분 살아 있다가 뒤늦게 나간다.
     *
     * <p><b>체크포인트를 가려 받는 이유.</b> "여유가 생겼다"는 사실은 "갈아탈 차가 곧 온다"
     * ({@code TRANSFER_TARGET_APPROACHING})를 무효로 만들지 않는다. 오히려 사용자가 정작 고개를
     * 들어야 하는 순간이다. 대상의 PENDING 을 통째로 지우면 가장 쓸모 있는 알림이 조용히 사라진다.
     *
     * <p>정상 전이에서는 이 필터가 실제로 무언가를 구하는 일이 드물다 — 접근 알림이 남아 있는
     * 채로 회복 경로가 도는 조합이 배선 순서상 거의 생기지 않는다. 그래도 둔다.
     * <b>"회복은 위험 알림만 지운다"는 불변식을 코드에 적어 두는 장치</b>이고, 순서가 유일한
     * 보호막이면 나중에 배선을 바꾸는 사람이 조용히 접근 알림을 지운다.
     *
     * <p>승격 경로({@link #supersedePendingByScope})는 반대로 가린 것이 없다. 그 쿼리는 이미
     * PENDING 만 건드리므로, 그 의미가 그대로 "아직 발송되지 않은 접근 알림만 취소"가 된다 —
     * 위험 알림이 나가는 마당에 뒤늦게 도착하는 "곧 온다"는 거짓 안심이다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("""
            update JourneyNotification n
               set n.status = watoo.grd.nextroute.domain.journey.entity.JourneyNotificationStatus.CANCELED,
                   n.lastFailureReason = 'superseded'
             where n.journeyId = :journeyId
               and n.routeRevision = :routeRevision
               and n.scopeType = :scopeType
               and n.scopeId = :scopeId
               and n.status = watoo.grd.nextroute.domain.journey.entity.JourneyNotificationStatus.PENDING
               and n.checkpoint in :checkpoints
            """)
    int supersedeAllPendingByScope(@Param("journeyId") Long journeyId,
                                   @Param("routeRevision") int routeRevision,
                                   @Param("scopeType") JourneyNotificationScope scopeType,
                                   @Param("scopeId") int scopeId,
                                   @Param("checkpoints") Collection<JourneyCheckpoint> checkpoints);

    /** claim 이 돌려주는 한 줄: 행 번호와 증서(올린 뒤의 {@code attempt_count}). */
    interface ClaimRow {
        Long getId();

        Integer getToken();
    }

    /**
     * relay 배치 claim: PENDING → PROCESSING(SKIP LOCKED).
     *
     * <p>{@code attempt_count} 를 여기서 올린다 — 발송 직전 재확인이 취소한 행도 시도를 하나
     * 쓴다는 뜻이다(버스와 동일, 수용). 올린 값이 그대로 증서다(#451).
     *
     * <p>대상 고르기({@code scheduled_at <= :now}, 순서)는 적재와 같은 앱 시계를 쓴다. 적재가 {@code scheduled_at} 을 앱
     * 시계로 찍기 때문이다. 반면 {@code processing_started_at} 은 heartbeat·회수와 같은 DB {@code now()} 로 찍는다 —
     * 회수가 인스턴스 시계 차이에 흔들리지 않게(#451).
     *
     * <p>{@code @Modifying} 이 아니다(RETURNING). {@code @Transactional} 필수.
     */
    @Query(value = """
            WITH picked AS (
                SELECT o.id FROM journey_notification o
                WHERE o.status = 'PENDING' AND o.scheduled_at <= :now AND o.deleted_at IS NULL
                ORDER BY o.scheduled_at
                LIMIT :batchSize
                FOR UPDATE OF o SKIP LOCKED)
            UPDATE journey_notification n
            SET status = 'PROCESSING', processing_started_at = now(),
                attempt_count = n.attempt_count + 1, updated_at = :auditNow
            FROM picked
            WHERE n.id = picked.id
            RETURNING n.id AS id, n.attempt_count AS token
            """, nativeQuery = true)
    List<ClaimRow> claimBatchForProcessing(@Param("now") Instant now, @Param("batchSize") int batchSize,
                                           @Param("auditNow") LocalDateTime auditNow);

    /**
     * 우선순위 먼저 claim(#451 PR-2, {@code relay.claim-order=PRIORITY_FIRST}). 대상·잠금·증서는
     * {@link #claimBatchForProcessing} 과 같고 순서만 다르다.
     *
     * <p>순서 = {@code CASE WHEN created_at <= :agedBefore THEN 0 ELSE priority END, scheduled_at}. 오래 기다린 행은
     * 등급과 무관하게 긴급과 동급이 된다(굶주림 방지). 기준이 {@code scheduled_at} 이 아니라 {@code created_at} 인 이유:
     * 일시 실패 재시도와 회수가 {@code scheduled_at} 을 뒤로 미루므로 그걸 기준으로 하면 기다린 시간을 처음부터 다시 센다.
     * {@code created_at} 은 감사 칼럼({@code timestamp(6)}, 앱 시계)이라 {@code agedBefore} 도 앱 시계 {@link LocalDateTime} 이다.
     *
     * <p>정렬식 때문에 인덱스로 정렬을 끝내지 못한다(PENDING 이 수백 건 규모라 수용, 노트에 EXPLAIN).
     */
    @Query(value = """
            WITH picked AS (
                SELECT o.id FROM journey_notification o
                WHERE o.status = 'PENDING' AND o.scheduled_at <= :now AND o.deleted_at IS NULL
                ORDER BY CASE WHEN o.created_at <= :agedBefore THEN 0 ELSE o.priority END, o.scheduled_at
                LIMIT :batchSize
                FOR UPDATE OF o SKIP LOCKED)
            UPDATE journey_notification n
            SET status = 'PROCESSING', processing_started_at = now(),
                attempt_count = n.attempt_count + 1, updated_at = :auditNow
            FROM picked
            WHERE n.id = picked.id
            RETURNING n.id AS id, n.attempt_count AS token
            """, nativeQuery = true)
    List<ClaimRow> claimBatchForProcessingPriorityFirst(@Param("now") Instant now,
                                                        @Param("agedBefore") LocalDateTime agedBefore,
                                                        @Param("batchSize") int batchSize,
                                                        @Param("auditNow") LocalDateTime auditNow);

    /**
     * heartbeat(#451). 이 인스턴스가 들고 있는 (행, 증서) 쌍의 {@code processing_started_at} 을 DB {@code now()} 로 민다.
     * 증서가 다르면(이미 회수돼 남이 잡았으면) 건드리지 않는다.
     *
     * @param ids    {@code {1,2,3}} 꼴의 bigint 배열 문자열
     * @param tokens 같은 순서의 int 배열 문자열
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = """
            UPDATE journey_notification n
               SET processing_started_at = now()
              FROM unnest(cast(:ids as bigint[]), cast(:tokens as int[])) AS t(id, token)
             WHERE n.id = t.id
               AND n.status = 'PROCESSING'
               AND n.attempt_count = t.token
            """, nativeQuery = true)
    int heartbeat(@Param("ids") String ids, @Param("tokens") String tokens);

    /**
     * 고착 PROCESSING 회수 → PENDING({@code scheduledAt = now}). 워커가 죽었을 때 소유권 회수.
     *
     * <p>기준 시각은 DB {@code now()} 다(#451, heartbeat 와 같은 시계). 회수할 때 {@code requeue_count} 를 올린다 — 시도
     * 상한은 {@code attempt_count − requeue_count}(실제 발송 시도)로 센다. {@code attempt_count} 는 그대로라 다음 claim 이
     * 올리면서 옛 증서가 무효가 된다.
     *
     * @param now 다시 집힐 시각({@code scheduled_at}). claim 과 같은 앱 시계
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE journey_notification
               SET status = 'PENDING', scheduled_at = :now,
                   requeue_count = requeue_count + 1, last_requeued_at = now(), updated_at = :auditNow
             WHERE status = 'PROCESSING'
               AND processing_started_at < now() - make_interval(secs => :timeoutSeconds)
            """, nativeQuery = true)
    int reclaimStale(@Param("timeoutSeconds") double timeoutSeconds, @Param("now") Instant now,
                     @Param("auditNow") LocalDateTime auditNow);

    // ── 증서 조건부 상태 변경(#451) ───────────────────────────────────────────────
    //
    // 엔티티 저장으로 바꾸면 안 된다. JourneyNotification 에 @Version·@DynamicUpdate 가 없어 dirty check UPDATE 가 모든
    // 칼럼을 옛 값으로 덮는다 — 회수·재claim 이 올린 attempt_count 를 되돌려 울타리가 깨진다. 그래서 전부 한 문장의
    // 조건부 UPDATE 다. 0행이면 부르는 쪽이 StaleClaimException 을 던져 같은 트랜잭션을 되돌린다.

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = """
            UPDATE journey_notification
               SET status = 'SENT', sent_at = :sentAt, updated_at = :auditNow
             WHERE id = :id AND status = 'PROCESSING' AND attempt_count = :token
            """, nativeQuery = true)
    int markSentIfClaimed(@Param("id") long id, @Param("token") int token, @Param("sentAt") Instant sentAt,
                          @Param("auditNow") LocalDateTime auditNow);

    /** 끝 상태(CANCELED·FAILED·DEAD)로 옮긴다. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = """
            UPDATE journey_notification
               SET status = :status, last_failure_reason = :reason, updated_at = :auditNow
             WHERE id = :id AND status = 'PROCESSING' AND attempt_count = :token
            """, nativeQuery = true)
    int finishIfClaimed(@Param("id") long id, @Param("token") int token, @Param("status") String status,
                        @Param("reason") String reason, @Param("auditNow") LocalDateTime auditNow);

    /** 일시 실패 → 백오프 시각으로 PENDING 복귀. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = """
            UPDATE journey_notification
               SET status = 'PENDING', last_failure_reason = :reason, scheduled_at = :nextAttemptAt,
                   updated_at = :auditNow
             WHERE id = :id AND status = 'PROCESSING' AND attempt_count = :token
            """, nativeQuery = true)
    int releaseToPendingIfClaimed(@Param("id") long id, @Param("token") int token, @Param("reason") String reason,
                                  @Param("nextAttemptAt") Instant nextAttemptAt,
                                  @Param("auditNow") LocalDateTime auditNow);

    /**
     * 여정 종료 시 미발송 취소.
     *
     * <p>PENDING 만 건드린다. PROCESSING 은 이미 발송 중이라 {@code REQUIRES_NEW} 로 도는
     * {@code markSent}/{@code markTransientFailure} 와 충돌한다 — 그쪽은 relay 의 발송 직전
     * 재확인이 잡는다.
     *
     * <p>{@code clearAutomatically = false} 다(버스의 {@code cancelPendingByAlertId} 와 다름).
     * {@code JourneyCommandService} 는 취소 뒤에도 여정 엔티티를 만진다(명령 번호 증가, 스냅샷
     * 생성). 영속성 컨텍스트를 비우면 그 변경이 조용히 사라진다. 이 트랜잭션들이
     * {@code JourneyNotification} 을 읽은 적은 없어 비울 이유도 없다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("""
            update JourneyNotification n
               set n.status = watoo.grd.nextroute.domain.journey.entity.JourneyNotificationStatus.CANCELED,
                   n.lastFailureReason = 'journey-ended'
             where n.journeyId = :journeyId
               and n.status = watoo.grd.nextroute.domain.journey.entity.JourneyNotificationStatus.PENDING
            """)
    int cancelPendingByJourneyId(@Param("journeyId") Long journeyId);

    /**
     * 사유를 받아 취소한다. 재탐색 수락(⑤)이 이전 개정본의 알림을 접을 때 쓴다.
     *
     * <p>{@link #cancelPendingByJourneyId} 와 나눠 둔 이유는 <b>사유가 곧 운영 신호</b>이기
     * 때문이다. 끝나지도 않은 여정의 알림을 {@code journey-ended} 로 세면 "여정이 끝나서 못 간
     * 알림"의 지표가 오염되고, "경로가 바뀌어서 접힌 알림"은 세어지지 않는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("""
            update JourneyNotification n
               set n.status = watoo.grd.nextroute.domain.journey.entity.JourneyNotificationStatus.CANCELED,
                   n.lastFailureReason = :reason
             where n.journeyId = :journeyId
               and n.status = watoo.grd.nextroute.domain.journey.entity.JourneyNotificationStatus.PENDING
            """)
    int cancelPendingByJourneyId(@Param("journeyId") Long journeyId,
                                 @Param("reason") String reason);

    /** 서버 lease 만료 배치용. 만료된 여정 묶음의 PENDING 을 한 문장으로 취소한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("""
            update JourneyNotification n
               set n.status = watoo.grd.nextroute.domain.journey.entity.JourneyNotificationStatus.CANCELED,
                   n.lastFailureReason = 'journey-ended'
             where n.journeyId in :journeyIds
               and n.status = watoo.grd.nextroute.domain.journey.entity.JourneyNotificationStatus.PENDING
            """)
    int cancelPendingByJourneyIdIn(@Param("journeyIds") Collection<Long> journeyIds);

    /**
     * 아직 안 나간 환승 위험 알림을 거둔다 — 같은 순간 재탐색 push 가 대안을 들고 나갔다(#264 PR-5).
     *
     * <p>PENDING 만 건드린다. 이미 발송 중(PROCESSING)인 것은 둘 다 나가는 것으로 받아들인다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("""
            update JourneyNotification n
               set n.status = watoo.grd.nextroute.domain.journey.entity.JourneyNotificationStatus.CANCELED,
                   n.lastFailureReason = 'superseded-by-reroute'
             where n.journeyId = :journeyId
               and n.routeRevision = :routeRevision
               and n.status = watoo.grd.nextroute.domain.journey.entity.JourneyNotificationStatus.PENDING
               and n.checkpoint in :checkpoints
            """)
    int cancelPendingByCheckpoints(@Param("journeyId") Long journeyId,
                                   @Param("routeRevision") int routeRevision,
                                   @Param("checkpoints") Collection<JourneyCheckpoint> checkpoints);

    // ── 울릴 권리용 취소(#428 PR-1b) ─────────────────────────────────────────────
    //
    // 위의 취소들과 하는 일은 같고, <b>실제로 이 문장이 PENDING → CANCELED 로 옮긴 행의 논리 키</b>를 돌려준다는
    // 점만 다르다. 물려받기는 "내가 취소한 행이 권리를 갖고 있었다"일 때만 맞다 — relay 가 같은 순간 그 행을
    // PROCESSING 으로 집어 갔으면 이 문장은 그 행을 건너뛰고(READ COMMITTED 재평가) 키도 돌려주지 않는다.
    // 따로 SELECT 한 뒤 UPDATE 하면 그 사이에 집혀 간 행까지 물려받아 두 번 울린다.
    //
    // {@code @Modifying} 이 아니다(RETURNING). 반드시 {@code @Transactional} 안에서 부른다. 피로 정책이 꺼져
    // 있으면 부르지 않는다 — 꺼짐은 위의 옛 쿼리 그대로다.

    /**
     * 한 대상(환승)의 위험 알림 PENDING 을 취소한다. 승격(빠듯→놓침)과 회복이 쓴다.
     *
     * @param exceptLogicalKey 남길 논리 키(방금 만들 알림). 회복처럼 남길 것이 없으면 빈 문자열
     * @return 취소된 행의 논리 키(채널마다 한 줄이라 중복될 수 있다)
     */
    @Query(value = """
            UPDATE journey_notification
               SET status = 'CANCELED', last_failure_reason = :reason
             WHERE journey_id = :journeyId
               AND route_revision = :routeRevision
               AND scope_type = :scopeType
               AND scope_id = :scopeId
               AND status = 'PENDING'
               AND checkpoint IN (:checkpoints)
               AND logical_checkpoint_key <> :exceptLogicalKey
            RETURNING logical_checkpoint_key
            """, nativeQuery = true)
    List<String> cancelPendingByScopeReturningKeys(@Param("journeyId") Long journeyId,
                                                   @Param("routeRevision") int routeRevision,
                                                   @Param("scopeType") String scopeType,
                                                   @Param("scopeId") int scopeId,
                                                   @Param("checkpoints") Collection<String> checkpoints,
                                                   @Param("exceptLogicalKey") String exceptLogicalKey,
                                                   @Param("reason") String reason);

    /**
     * 경로 버전 전체의 위험 알림 PENDING 을 취소한다. 보드 push 가 대안을 들고 나갈 때 쓴다
     * ({@link #cancelPendingByCheckpoints} 의 키 반환판, 사유도 같은 {@code superseded-by-reroute}).
     *
     * @return 취소된 행의 논리 키(채널마다 한 줄이라 중복될 수 있다)
     */
    @Query(value = """
            UPDATE journey_notification
               SET status = 'CANCELED', last_failure_reason = 'superseded-by-reroute'
             WHERE journey_id = :journeyId
               AND route_revision = :routeRevision
               AND status = 'PENDING'
               AND checkpoint IN (:checkpoints)
            RETURNING logical_checkpoint_key
            """, nativeQuery = true)
    List<String> cancelPendingByCheckpointsReturningKeys(@Param("journeyId") Long journeyId,
                                                         @Param("routeRevision") int routeRevision,
                                                         @Param("checkpoints") Collection<String> checkpoints);

    /**
     * 그 환승의 대기 중인 위험 알림 중 가장 이른 발송 예정 시각(epoch 초). 없으면 null.
     *
     * <p>합치기 창(#428)의 상한을 잰다 — 보류 중인 울림 알림이 더 나쁜 알림으로 대체돼도 첫 판정의 보류가 끝나는
     * 시각보다 늦게 울리지 않게 한다. 대체(취소)하기 <b>전에</b> 불러야 한다.
     */
    @Query(value = """
            SELECT CAST(EXTRACT(EPOCH FROM MIN(scheduled_at)) AS bigint)
              FROM journey_notification
             WHERE journey_id = :journeyId
               AND route_revision = :routeRevision
               AND scope_type = :scopeType
               AND scope_id = :scopeId
               AND status = 'PENDING'
               AND checkpoint IN (:checkpoints)
            """, nativeQuery = true)
    Long findEarliestPendingScheduledEpoch(@Param("journeyId") Long journeyId,
                                           @Param("routeRevision") int routeRevision,
                                           @Param("scopeType") String scopeType,
                                           @Param("scopeId") int scopeId,
                                           @Param("checkpoints") Collection<String> checkpoints);
}
