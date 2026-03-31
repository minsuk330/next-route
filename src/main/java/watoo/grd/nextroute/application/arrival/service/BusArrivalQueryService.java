package watoo.grd.nextroute.application.arrival.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.arrival.dto.BusArrivalResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetBusArrivalUseCase;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BusArrivalQueryService implements GetBusArrivalUseCase {

    private final BusDataService busDataService;
    private static final int WINDOW_MINUTES = 15;

    @Override
    public List<BusArrivalResponse> getArrivals(String stopId) {
        LocalDateTime from = LocalDateTime.now().minusMinutes(WINDOW_MINUTES);
        return busDataService.findLatestArrivalsByStopId(stopId, from).stream()
                .map(raw -> BusArrivalResponse.builder()
                        .routeId(raw.getRouteId())
                        .arrivalMsg1(raw.getArrivalMsg1())
                        .predictTime1(raw.getPredictTime1())
                        .congestionNum1(raw.getCongestionNum1())
                        .arrivalMsg2(raw.getArrivalMsg2())
                        .predictTime2(raw.getPredictTime2())
                        .congestionNum2(raw.getCongestionNum2())
                        .build())
                .toList();
    }
}
