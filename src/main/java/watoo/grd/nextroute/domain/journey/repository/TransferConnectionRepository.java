package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;

import java.util.List;

public interface TransferConnectionRepository extends JpaRepository<TransferConnection, Long> {

    List<TransferConnection> findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
            Long journeyId, int routeRevision);
}
