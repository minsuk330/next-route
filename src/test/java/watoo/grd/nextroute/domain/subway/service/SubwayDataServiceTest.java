package watoo.grd.nextroute.domain.subway.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import watoo.grd.nextroute.application.subway.service.SubwayArrivalEventDerivationService;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;

@SpringBootTest
class SubwayDataServiceTest {

  @Autowired
  SubwayDataService subwayDataService;
  @Autowired
  SubwayArrivalEventDerivationService eventService;


  @Test
  void flow_test(){
    /// given
    String from = "2026-05-05 16:01:51";
    String to = "2026-05-06 17:01:51";

    int i = eventService.deriveForDate(LocalDate.now());
    /// when
    System.out.println(i);

  }

}