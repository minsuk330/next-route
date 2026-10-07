package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface JourneyLegRepository extends JpaRepository<JourneyLeg, Long> {

    /** 한 개정본의 단계 전부를 순서대로. 화면과 상태 판단이 모두 이 순서를 전제한다. */
    List<JourneyLeg> findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
            Long journeyId, int routeRevision);

    /**
     * 더 최신 관측일 때만 progress 요약을 덮어쓴다.
     *
     * <p>여정 행을 잠그지 않는 것이 핵심이다. progress 는 여정마다 15초에 한 번씩 들어오는데
     * 명령 처리와 같은 잠금을 쓰면 둘이 계속 부딪친다. 대신 "지금 값보다 최신일 때만"을 조건에
     * 넣어 한 문장으로 끝낸다 — 읽고 판단해서 쓰는 사이의 틈이 없다.
     *
     * <p>갱신된 행이 0이면 재전송·역순·같은 시각 충돌 중 하나다. 어느 쪽인지는 부르는 쪽이
     * 현재 값을 다시 읽어 가른다.
     *
     * @return 갱신된 행 수. 0 또는 1
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update JourneyLeg l
               set l.lastProgressId = :progressId,
                   l.progressGeometrySegmentIndex = :geometrySegmentIndex,
                   l.progressFraction = :segmentProgress,
                   l.progressLegFraction = :legProgress,
                   l.progressSectionId = :sectionId,
                   l.remainingDistanceMeters = :remainingDistanceMeters,
                   l.remainingDurationSeconds = :remainingDurationSeconds,
                   l.remainingStops = :remainingStops,
                   l.progressObservedAt = :observedAt,
                   l.evidenceSource = :evidenceSource,
                   l.evidenceObservedAt = :observedAt,
                   l.evidenceQuality = :quality
             where l.id = :legId
               and l.deletedAt is null
               and (l.progressObservedAt is null or l.progressObservedAt < :observedAt)
            """)
    int applyProgressIfNewer(@Param("legId") Long legId,
                             @Param("progressId") UUID progressId,
                             @Param("geometrySegmentIndex") Integer geometrySegmentIndex,
                             @Param("segmentProgress") BigDecimal segmentProgress,
                             @Param("legProgress") BigDecimal legProgress,
                             @Param("sectionId") String sectionId,
                             @Param("remainingDistanceMeters") Integer remainingDistanceMeters,
                             @Param("remainingDurationSeconds") Integer remainingDurationSeconds,
                             @Param("remainingStops") Integer remainingStops,
                             @Param("observedAt") Instant observedAt,
                             @Param("evidenceSource") String evidenceSource,
                             @Param("quality") String quality);

    /**
     * 탈퇴: 그 사용자의 여정에 속한 단계를 소프트 삭제한다(ADR 0001, 여정 소프트 삭제 계열).
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update JourneyLeg l set l.deletedAt = :now
             where l.journeyId in (select s.id from JourneySession s where s.userId = :userId)
               and l.deletedAt is null
            """)
    int softDeleteByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    // ── 단독 추적 버스 차량(#265 PR-4) ─────────────────────────────────────────
    // 엔티티 저장은 차량 칼럼을 쓰지 않는다(updatable = false). 여기 세 문장만 쓴다. 판정이 끝난 행
    // (vehicle_matched_at 있음)은 다시 판정하지 않는다 — 재시도·중복 tick 이 결과를 뒤집지 못하게 조건에 넣는다.

    /** 차량을 고정한다. 이미 판정이 끝났으면 0. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE journey_leg
               SET vehicle_id = :vehicleId, vehicle_id_type = :idType, vehicle_source = :source,
                   vehicle_matched_at = :at
             WHERE id = :legId AND vehicle_matched_at IS NULL
            """, nativeQuery = true)
    int assignVehicle(@Param("legId") Long legId, @Param("vehicleId") String vehicleId,
                      @Param("idType") String idType, @Param("source") String source, @Param("at") Instant at);

    /** 못 찾음·포기(2대 이상 갈리지 않음, 예산 소진 등). 차량 없이 판정만 끝낸다. 이미 끝났으면 0. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE journey_leg SET vehicle_matched_at = :at
             WHERE id = :legId AND vehicle_matched_at IS NULL
            """, nativeQuery = true)
    int finishVehicleUnmatched(@Param("legId") Long legId, @Param("at") Instant at);

    /** 사용자가 "이 버스 아님". 고정을 지우고 판정은 끝난 채로 둔다(이미 타 있어 다시 관찰하지 않는다). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE journey_leg
               SET vehicle_id = NULL, vehicle_id_type = NULL, vehicle_source = NULL,
                   vehicle_matched_at = :at
             WHERE id = :legId
            """, nativeQuery = true)
    int resetVehicle(@Param("legId") Long legId, @Param("at") Instant at);
}
