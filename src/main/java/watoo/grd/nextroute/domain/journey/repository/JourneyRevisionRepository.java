package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevision;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevisionStatus;

import java.util.Optional;
import java.util.UUID;

public interface JourneyRevisionRepository extends JpaRepository<JourneyRevision, Long> {

    /** 지금 안내 중인 경로. {@code uq_journey_active_revision} 덕분에 최대 한 건이다. */
    Optional<JourneyRevision> findByJourneyIdAndStatusAndDeletedAtIsNull(
            UUID journeyId, JourneyRevisionStatus status);
}
