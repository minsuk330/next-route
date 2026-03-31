package watoo.grd.nextroute.infrastructure.adapter.in.api.arrival;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.arrival.dto.SubwayArrivalResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayArrivalUseCase;

import java.util.List;

@RestController
@RequestMapping("/api/arrivals/subway")
@RequiredArgsConstructor
public class SubwayArrivalController {

    private final GetSubwayArrivalUseCase getSubwayArrivalUseCase;

    @GetMapping("/{stationId}")
    public ResponseEntity<List<SubwayArrivalResponse>> getArrivals(@PathVariable String stationId) {
        return ResponseEntity.ok(getSubwayArrivalUseCase.getArrivals(stationId));
    }
}
