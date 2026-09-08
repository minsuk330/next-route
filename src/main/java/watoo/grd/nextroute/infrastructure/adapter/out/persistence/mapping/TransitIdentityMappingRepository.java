package watoo.grd.nextroute.infrastructure.adapter.out.persistence.mapping;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TransitIdentityMappingRepository extends JpaRepository<TransitIdentityMappingEntity, Long> {

    /** tripPattern 은 null 일 수 있어 명시 처리. (버전+canonical 키)로 조회, 여러 provider 가능. */
    @Query("""
            select m from TransitIdentityMappingEntity m
            where m.dataVersion = :dataVersion
              and m.feedId = :feedId
              and m.agencyId = :agencyId
              and m.sourceRouteId = :sourceRouteId
              and m.sourceStopId = :sourceStopId
              and m.occurrenceIndex = :occurrenceIndex
              and ((:tripPattern is null and m.tripPattern is null) or m.tripPattern = :tripPattern)
            """)
    List<TransitIdentityMappingEntity> findMappings(
            @Param("dataVersion") String dataVersion,
            @Param("feedId") String feedId,
            @Param("agencyId") String agencyId,
            @Param("sourceRouteId") String sourceRouteId,
            @Param("tripPattern") String tripPattern,
            @Param("sourceStopId") String sourceStopId,
            @Param("occurrenceIndex") int occurrenceIndex);
}
