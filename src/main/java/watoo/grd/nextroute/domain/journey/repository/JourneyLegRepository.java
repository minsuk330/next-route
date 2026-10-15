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
     * 이 개정본에 주어진 종류의 단계가 하나라도 있는지. 평가기가 어느 공급자 슬롯(버스·지하철)을 잡을지 고르는 데
     * 쓴다(#427 처방 2차). 지난 단계도 센다 — 평가기는 지난 환승도 다시 판정하므로 보수적으로 본다.
     */
    @Query("""
            select count(l) > 0 from JourneyLeg l
             where l.journeyId = :journeyId and l.routeRevision = :routeRevision
               and l.deletedAt is null and l.type in :types
            """)
    boolean existsLegOfTypes(@Param("journeyId") Long journeyId, @Param("routeRevision") int routeRevision,
                             @Param("types") java.util.Collection<watoo.grd.nextroute.domain.journey.entity.JourneyLegType> types);

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
}
