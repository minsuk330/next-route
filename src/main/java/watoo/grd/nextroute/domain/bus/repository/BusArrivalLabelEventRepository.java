package watoo.grd.nextroute.domain.bus.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalLabelEvent;

import java.time.LocalDate;
import java.util.List;

public interface BusArrivalLabelEventRepository extends JpaRepository<BusArrivalLabelEvent, Long> {

    @Modifying
    @Query("DELETE FROM BusArrivalLabelEvent e WHERE e.serviceDate = :serviceDate")
    int deleteByServiceDate(@Param("serviceDate") LocalDate serviceDate);

    long countByServiceDate(LocalDate serviceDate);

    List<BusArrivalLabelEvent> findByServiceDate(LocalDate serviceDate);
}
