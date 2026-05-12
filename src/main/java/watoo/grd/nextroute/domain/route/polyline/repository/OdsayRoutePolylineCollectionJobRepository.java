package watoo.grd.nextroute.domain.route.polyline.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.route.polyline.entity.CollectionJobStatus;
import watoo.grd.nextroute.domain.route.polyline.entity.OdsayRoutePolylineCollectionJob;

import java.util.List;
import java.util.Optional;

public interface OdsayRoutePolylineCollectionJobRepository
        extends JpaRepository<OdsayRoutePolylineCollectionJob, Long> {

    Optional<OdsayRoutePolylineCollectionJob> findByOdsayRouteIdAndLaneClass(
            String odsayRouteId, int laneClass);

    List<OdsayRoutePolylineCollectionJob> findByStatusOrderByRequestedAtAsc(
            CollectionJobStatus status, Pageable pageable);

    @Modifying
    @Query("UPDATE OdsayRoutePolylineCollectionJob j SET j.status = :status WHERE j.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") CollectionJobStatus status);
}
