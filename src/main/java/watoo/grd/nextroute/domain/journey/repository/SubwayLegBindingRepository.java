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
     * 그 개정본·단계의 고정. <b>발송 직전 세대 대조</b>가 쓴다.
     *
     * <p>단계 id 가 아니라 좌표로 찾는 이유는 대기열 행이 좌표만 들고 있기 때문이다 — 재탐색이
     * 단계를 복제하므로 옛 id 로 찾으면 지금 살아 있는 고정을 못 본다.
     */
    Optional<SubwayLegBinding> findByJourneyIdAndRouteRevisionAndLegIndexAndDeletedAtIsNull(
            Long journeyId, int routeRevision, int legIndex);

    /**
     * 여러 단계의 고정을 한 번에.
     *
     * <p>여정 스냅샷을 조립할 때 쓴다. 단계마다 따로 물으면 지연 로딩이 단계 수만큼 질의를
     * 만든다 — 조립 루프 안에서는 반드시 이쪽을 쓴다.
     */
    List<SubwayLegBinding> findByJourneyLegIdIn(List<Long> journeyLegIds);

    /**
     * 진행 링 갱신 대상. <b>열차가 정해진 고정({@code PROVISIONAL}·{@code CONFIRMED})을 본다.</b>
     *
     * <p>{@code PROVISIONAL} 은 자동 판정기(2026-10 제거)가 남긴 기존 행 때문에 남겨 둔다 — 2026-09-29 에 잠정도
     * 그리기로 했다(사용자 결정). 새 고정은 사용자 선택이라 {@code CONFIRMED} 로만 생긴다.
     *
     * <p>{@code PENDING} 은 여전히 뺀다 — 고른 열차가 아예 없다.
     *
     * <p><b>사용자가 역에서 고르고 아직 움직이지 않은 고정도 넣는다</b>(#304, {@code USER_SELECTED} + 이동 순번 없음).
     * 직접 고른 열차라 이동 관측을 기다리지 않는다 — 기다리면 고르는 순간부터 탈 때까지 Live Activity 가 비어 있고,
     * 모션 감지가 놓치면 끝까지 비어 있었다. 열차가 아직 승차역 전이면 링은 승차역부터 센다(갱신기가 정한다).
     * 새 행은 다음 tick(5초) 에 잡힌다 — 배치 상한이 진행 중 여정 수보다 크다.
     *
     * <p>세션 상태·개정본 조건이 없으면 끝난 여정과 옛 개정본의 확정 고정을 영원히 갱신한다. 이 표는 행을 지우지 않으므로 그 갱신은 스스로
     * 멈추지 않는다.
     *
     * <p><b>여정이 그 탑승의 바로 앞 대기이거나 그 탑승 중일 때만</b> 돈다. 내린 뒤에도 돌면 지하철 push 가 같은 Activity
     * 토큰으로 다음 도보·버스 화면을 덮어쓴다. 대기(leg-1)는 남긴다 — 고르고 기다리는 동안의 링(#304). 종료 프레임은
     * 이 목록이 아니라 {@code SubwayRingPushEnqueueService.enqueueTerminal} 이 만든다.
     */
    @Query(value = """
            SELECT b.* FROM subway_leg_binding b
              JOIN journey_leg l     ON l.id = b.journey_leg_id
              JOIN journey_session s ON s.id = b.journey_id
             WHERE b.status IN ('PROVISIONAL','CONFIRMED')
               AND b.deleted_at IS NULL
               AND l.deleted_at IS NULL
               AND s.status = 'ACTIVE'
               AND b.route_revision = s.route_revision
               AND s.current_leg_index IN (b.leg_index - 1, b.leg_index)
             ORDER BY b.updated_at
             LIMIT :limit
            """, nativeQuery = true)
    List<SubwayLegBinding> findRingTargets(@Param("limit") int limit);

    /**
     * 사용자 요청 경로가 쓰는 잠금 조회. 이쪽은 건너뛸 수 없어 <b>기다리되 짧게</b> 기다린다.
     *
     * <p>사용자 선택이 이걸 쓴다. 잠금 없이 읽고 더티체킹으로 고치면, 그 사이 다른 요청이 쓴 결과가
     * <b>낡은 스냅샷 전 칼럼에 덮여 조용히 사라진다</b> — 이 엔티티에는
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
