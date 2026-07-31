package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;

import java.util.List;
import java.util.UUID;

public interface TransferConnectionRepository extends JpaRepository<TransferConnection, Long> {

    List<TransferConnection> findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
            UUID journeyId, int routeRevision);
}
