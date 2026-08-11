package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;

import java.util.List;
import java.util.Optional;

public interface TransferConnectionRepository extends JpaRepository<TransferConnection, Long> {

    List<TransferConnection> findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
            Long journeyId, int routeRevision);

    /**
     * 환승 하나. 발송 직전 재확인이 접근 알림의 근거가 아직 살아 있는지 볼 때 쓴다.
     *
     * <p>목록을 통째로 읽지 않는다 — 알림 한 건이 보는 것은 자기 환승 하나뿐이다.
     */
    Optional<TransferConnection> findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
            Long journeyId, int routeRevision, int transferIndex);
}
