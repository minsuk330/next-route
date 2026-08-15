package watoo.grd.nextroute.domain.journey.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubwayLegBindingRepository extends JpaRepository<SubwayLegBinding, Long> {

    Optional<SubwayLegBinding> findByJourneyLegId(Long journeyLegId);

    /**
     * 여러 단계의 고정을 한 번에.
     *
     * <p>여정 스냅샷을 조립할 때 쓴다. 단계마다 따로 물으면 지연 로딩이 단계 수만큼 질의를
     * 만든다 — 조립 루프 안에서는 반드시 이쪽을 쓴다.
     */
    List<SubwayLegBinding> findByJourneyLegIdIn(List<Long> journeyLegIds);

    /**
     * 판정 대상. <b>살아 있고, 지금 판정할 수 있는 것만</b> 본다.
     *
     * <p>세션 상태·개정본 조건이 없으면 끝난 여정과 옛 개정본의 {@code PENDING} 을 영원히
     * 재처리한다. 이 표는 행을 지우지 않으므로 그 재처리는 스스로 멈추지 않는다.
     *
     * <p>{@code last_movement_seq IS NOT NULL} 이 핵심이다. 정차 관측이 먼저 온 경우와 사용자
     * reset 직후는 이동 시작 근거가 없어 <b>영원히 판정 불가</b>인데, 이걸 빼면 가장 오래된 그런
     * 행들이 매 tick 배치를 통째로 차지해 <b>뒤 행이 굶는다</b>. 대신
     * {@link #abandonStaleWithoutMovement} 가 따로 접는다.
     */
    @Query(value = """
            SELECT b.* FROM subway_leg_binding b
              JOIN journey_leg l     ON l.id = b.journey_leg_id
              JOIN journey_session s ON s.id = b.journey_id
             WHERE b.status IN ('PENDING','PROVISIONAL')
               AND b.deleted_at IS NULL
               AND l.deleted_at IS NULL
               AND s.status = 'ACTIVE'
               AND b.route_revision = s.route_revision
               AND (b.status = 'PROVISIONAL' OR b.last_movement_seq IS NOT NULL)
             ORDER BY b.updated_at
             LIMIT :limit
            """, nativeQuery = true)
    List<SubwayLegBinding> findMatchable(@Param("limit") int limit);

    /**
     * 진행 링 갱신 대상. <b>{@code CONFIRMED} 만 본다.</b>
     *
     * <p>{@code PENDING}·{@code PROVISIONAL} 을 넣으면 아직 어느 열차인지 모르는 채로 다음 역과
     * 진행률이 만들어진다. 앱 위젯의 필드 대부분은 non-optional 이라 그 값이 <b>실시간처럼</b>
     * 그려지고, 틀렸을 때 사용자는 서버를 믿은 대가로 역을 지나친다. 미확정 구간의 화면은
     * "탑승 열차 확인 중"이지 0% 링이 아니다.
     *
     * <p>세션 상태·개정본 조건은 {@link #findMatchable} 과 같은 이유로 있다 — 없으면 끝난 여정과
     * 옛 개정본의 확정 고정을 영원히 갱신한다. 이 표는 행을 지우지 않으므로 그 갱신은 스스로
     * 멈추지 않는다.
     */
    @Query(value = """
            SELECT b.* FROM subway_leg_binding b
              JOIN journey_leg l     ON l.id = b.journey_leg_id
              JOIN journey_session s ON s.id = b.journey_id
             WHERE b.status = 'CONFIRMED'
               AND b.deleted_at IS NULL
               AND l.deleted_at IS NULL
               AND s.status = 'ACTIVE'
               AND b.route_revision = s.route_revision
             ORDER BY b.updated_at
             LIMIT :limit
            """, nativeQuery = true)
    List<SubwayLegBinding> findRingTargets(@Param("limit") int limit);

    /**
     * 판정 직전에 그 행을 잡는다. <b>잠겨 있으면 기다리지 않고 빈손으로 돌아온다</b>(SKIP LOCKED).
     *
     * <p>워커가 사용자 요청을 기다릴 이유가 없다. 지금 누가 그 행을 만지는 중이면 다음 tick 에
     * 다시 오면 된다 — 대기가 아니라 재시도다.
     *
     * <p>{@code -2} 는 {@code LockOptions.SKIP_LOCKED} 다. 방언이 이 힌트를 무시하면 문장이
     * 조용히 그냥 {@code for update} 가 되어 대기로 바뀌므로, 발행 SQL 을 시험으로 고정한다
     * ({@code SubwayBindingLockSqlIT}).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select b from SubwayLegBinding b where b.journeyLegId = :legId and b.deletedAt is null")
    Optional<SubwayLegBinding> findForMatch(@Param("legId") Long legId);

    /**
     * 사용자 요청 경로가 쓰는 잠금 조회. 이쪽은 건너뛸 수 없어 <b>기다리되 짧게</b> 기다린다.
     *
     * <p>관측 수신·사용자 보정이 전부 이걸 쓴다. 잠금 없이 읽고 더티체킹으로 고치면, 그 사이
     * 판정기가 쓴 결과가 <b>낡은 스냅샷 전 칼럼에 덮여 조용히 사라진다</b> — 이 엔티티에는
     * {@code @Version} 도 {@code @DynamicUpdate} 도 없어 UPDATE 가 매핑 칼럼 전부를 쓴다.
     * 예외도 나지 않는다.
     *
     * <p>대기 상한은 JPA 힌트로 걸지 않는다. PostgreSQL 에는 {@code FOR UPDATE WAIT n} 이 없어
     * 임의 값이 그냥 무시된다 — 트랜잭션 안에서 {@code SET LOCAL lock_timeout} 을 건다
     * ({@code SubwayBindingLockGuard}).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from SubwayLegBinding b where b.journeyLegId = :legId and b.deletedAt is null")
    Optional<SubwayLegBinding> findForUpdate(@Param("legId") Long legId);

    /**
     * 판정 결과를 반영한다. <b>{@code CONFIRMED} 는 이 경로로 뒤집히지 않는다</b> — 사용자
     * 보정만 예외다.
     *
     * <p>순번까지 조건에 넣는 이유: 관측 수신은 순번만 올리고 {@code version} 은 올리지 않는다.
     * {@code version} 만 보면 <b>낡은 근거로 한 판정이 성공</b>한다.
     *
     * <p>{@code confidence} 는 건드리지 않는다. 그 값은 기기가 자기 센서를 얼마나 믿는지이고
     * 서버가 다시 계산하지 않기로 한 값이다 — 판정기가 덮으면 근거별 오판율을 못 잰다.
     *
     * <p>{@code IS NOT DISTINCT FROM} 을 쓰는 이유: 순번은 null 일 수 있고 {@code = null} 은
     * 어떤 값과도 참이 아니다. 캐스팅은 파라미터 타입 추론 실패를 막는다.
     *
     * @return 1 = 반영, 0 = 그 사이 누가 바꿨다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE subway_leg_binding
               SET status = :status, train_no = :trainNo, train_service_date = :serviceDate,
                   train_line_id = :lineId, train_direction = :direction,
                   version = version + 1, updated_at = :now
             WHERE journey_leg_id = :legId
               AND deleted_at IS NULL
               AND version = :expectedVersion
               AND reset_generation = :expectedGeneration
               AND last_movement_seq IS NOT DISTINCT FROM cast(:expectedMovementSeq as integer)
               AND last_episode_seq  IS NOT DISTINCT FROM cast(:expectedEpisodeSeq as integer)
               AND status IN ('PENDING','PROVISIONAL')
            """, nativeQuery = true)
    int casBind(@Param("legId") Long legId,
                @Param("status") String status,
                @Param("trainNo") String trainNo,
                @Param("serviceDate") LocalDate serviceDate,
                @Param("lineId") String lineId,
                @Param("direction") String direction,
                @Param("expectedVersion") int expectedVersion,
                @Param("expectedGeneration") int expectedGeneration,
                @Param("expectedMovementSeq") Integer expectedMovementSeq,
                @Param("expectedEpisodeSeq") Integer expectedEpisodeSeq,
                @Param("now") LocalDateTime now);

    /**
     * 판정 시간이 지나 접는다. <b>열차 identity 는 지우지 않는다</b> — 잠정으로 무엇을 보고
     * 있었는지가 사후 분석의 재료이고, {@code ABANDONED} 면 어차피 아무도 그 열차로 안내하지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE subway_leg_binding
               SET status = 'ABANDONED', version = version + 1, updated_at = :now
             WHERE journey_leg_id = :legId
               AND deleted_at IS NULL
               AND version = :expectedVersion
               AND reset_generation = :expectedGeneration
               AND last_movement_seq IS NOT DISTINCT FROM cast(:expectedMovementSeq as integer)
               AND last_episode_seq  IS NOT DISTINCT FROM cast(:expectedEpisodeSeq as integer)
               AND status IN ('PENDING','PROVISIONAL')
            """, nativeQuery = true)
    int casAbandon(@Param("legId") Long legId,
                   @Param("expectedVersion") int expectedVersion,
                   @Param("expectedGeneration") int expectedGeneration,
                   @Param("expectedMovementSeq") Integer expectedMovementSeq,
                   @Param("expectedEpisodeSeq") Integer expectedEpisodeSeq,
                   @Param("now") LocalDateTime now);

    /**
     * 이동 시작 근거 없이 {@code matchWindow} 를 넘긴 {@code PENDING} 을 접는다.
     *
     * <p>{@link #findMatchable} 이 일부러 안 보는 행들이다. 여기서 접지 않으면 영원히 남는다.
     * 사용자 reset 직후 행도 여기 걸리는데 의도한 것이다 — reset 뒤 새 관측이 영영 안 오면 그
     * 고정은 접히는 게 맞고, 접혀도 사용자 보정 API 는 그대로 동작한다(행이 남아 있다).
     *
     * @return 접은 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE subway_leg_binding
               SET status = 'ABANDONED', version = version + 1, updated_at = :now
             WHERE status = 'PENDING' AND deleted_at IS NULL
               AND last_movement_seq IS NULL
               AND updated_at < :cutoff
            """, nativeQuery = true)
    int abandonStaleWithoutMovement(@Param("now") LocalDateTime now,
                                    @Param("cutoff") LocalDateTime cutoff);

    // ── 수명주기(여정이 끝나거나 개정본이 오를 때) ──────────────────────────

    /**
     * 그 여정의 미완료 고정을 전부 접는다.
     *
     * <p><b>열차 identity 를 지우지 않는다.</b> 잠정으로 무엇을 보고 있었는지가 사후 분석의
     * 재료이고, {@code ABANDONED} 면 어차피 아무도 그 열차로 안내하지 않는다. 지우면 계승 규칙
     * ("과거 개정본 행은 identity 를 남긴다")과 정면으로 모순된다.
     *
     * <p>{@code CONFIRMED} 는 건드리지 않는다 — 감사·복구용 canonical 사실이다.
     *
     * <p>단계 조인 없이 한 문장으로 끝나는 것은 이 표가 {@code journey_id} 를 비정규화해 들고
     * 있기 때문이다. 부분 인덱스 {@code ix_subway_leg_binding_pending} 가 그대로 탄다.
     *
     * <p>{@code clearAutomatically} 를 켜지 않는다. 이 질의를 부르는 곳은 <b>여정 종료·재탐색
     * 트랜잭션 한가운데</b>라, 영속성 컨텍스트를 비우면 부르는 쪽이 들고 있던 세션·단계 엔티티가
     * 떨어져 나가 그 뒤의 변경이 조용히 사라진다.
     *
     * @return 접은 행 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = """
            UPDATE subway_leg_binding
               SET status = 'ABANDONED', version = version + 1, updated_at = :now
             WHERE journey_id = :journeyId AND deleted_at IS NULL
               AND status IN ('PENDING','PROVISIONAL')
            """, nativeQuery = true)
    int abandonOpenByJourney(@Param("journeyId") Long journeyId, @Param("now") LocalDateTime now);

    /**
     * 만료 배치용. 여정마다 문장을 날리면 배치 하나가 200 왕복이 된다.
     *
     * @return 접은 행 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = """
            UPDATE subway_leg_binding
               SET status = 'ABANDONED', version = version + 1, updated_at = :now
             WHERE journey_id IN (:journeyIds) AND deleted_at IS NULL
               AND status IN ('PENDING','PROVISIONAL')
            """, nativeQuery = true)
    int abandonOpenByJourneyIn(@Param("journeyIds") Collection<Long> journeyIds,
                               @Param("now") LocalDateTime now);

    /**
     * 개정본이 오를 때 <b>옛 개정본</b>의 미완료 고정만 접는다.
     *
     * <p>여정 전체를 접으면 방금 계승해 만든 새 개정본 행까지 함께 죽는다. 개정본 조건이 그
     * 경계다.
     *
     * @return 접은 행 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = """
            UPDATE subway_leg_binding
               SET status = 'ABANDONED', version = version + 1, updated_at = :now
             WHERE journey_id = :journeyId AND route_revision = :routeRevision
               AND deleted_at IS NULL
               AND status IN ('PENDING','PROVISIONAL')
            """, nativeQuery = true)
    int abandonOpenByJourneyAndRevision(@Param("journeyId") Long journeyId,
                                        @Param("routeRevision") int routeRevision,
                                        @Param("now") LocalDateTime now);

    /**
     * 계승 후보. 그 개정본에서 <b>열차를 이미 고른</b> 고정만 본다.
     *
     * <p>{@code PENDING} 은 이어받을 것이 없다 — 새 개정본의 단계에 행이 없으면 관측이 다시 올
     * 때 그때 열린다.
     */
    @Query("""
            select b from SubwayLegBinding b
             where b.journeyId = :journeyId
               and b.routeRevision = :routeRevision
               and b.deletedAt is null
               and b.status in (watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus.PROVISIONAL,
                                watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus.CONFIRMED)
            """)
    List<SubwayLegBinding> findBoundOfRevision(@Param("journeyId") Long journeyId,
                                               @Param("routeRevision") int routeRevision);
}
