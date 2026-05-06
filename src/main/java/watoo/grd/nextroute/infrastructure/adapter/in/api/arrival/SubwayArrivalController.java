package watoo.grd.nextroute.infrastructure.adapter.in.api.arrival;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.arrival.dto.SubwayArrivalResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayArrivalUseCase;

import java.util.List;

@RestController
@RequestMapping("/api/arrivals/subway")
@RequiredArgsConstructor
@Tag(name = "[공용] 지하철 도착 정보")
public class SubwayArrivalController {
  private final GetSubwayArrivalUseCase getSubwayArrivalUseCase;


  @GetMapping
  public ResponseEntity<List<SubwayArrivalResponse>> getArrivals(
      @RequestParam double lat,
      @RequestParam double lon,
      @RequestParam(required = false) Integer wayCode
  ) {
      return ResponseEntity.ok(getSubwayArrivalUseCase.getArrivals(lat, lon,wayCode));
  }
}
