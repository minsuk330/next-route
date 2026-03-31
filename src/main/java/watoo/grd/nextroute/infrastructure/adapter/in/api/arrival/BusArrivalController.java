package watoo.grd.nextroute.infrastructure.adapter.in.api.arrival;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.arrival.dto.BusArrivalResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetBusArrivalUseCase;

import java.util.List;

@RestController
@RequestMapping("/api/arrivals/bus")
@RequiredArgsConstructor
public class BusArrivalController {

    private final GetBusArrivalUseCase getBusArrivalUseCase;

    @GetMapping("/{stopId}")
    public ResponseEntity<List<BusArrivalResponse>> getArrivals(@PathVariable String stopId) {
        return ResponseEntity.ok(getBusArrivalUseCase.getArrivals(stopId));
    }
}
