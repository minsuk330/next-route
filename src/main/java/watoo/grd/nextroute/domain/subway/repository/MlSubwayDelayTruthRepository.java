package watoo.grd.nextroute.domain.subway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.subway.entity.MlSubwayDelayTruth;

import java.time.LocalDate;
import java.util.List;

public interface MlSubwayDelayTruthRepository extends JpaRepository<MlSubwayDelayTruth, Long> {

    @Modifying
    @Query("DELETE FROM MlSubwayDelayTruth t WHERE t.serviceDate = :serviceDate")
    int deleteByServiceDate(@Param("serviceDate") LocalDate serviceDate);

    List<MlSubwayDelayTruth> findByServiceDate(LocalDate serviceDate);

    long countByServiceDate(LocalDate serviceDate);
}
