package watoo.grd.nextroute.application.arrival.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.arrival.dto.BusArrivalResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetBusArrivalUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BusArrivalQueryService implements GetBusArrivalUseCase {

    private final BusApiPort busApiPort;

    @Override
    public List<BusArrivalResponse> getArrivals(String stopId) {
        return busApiPort.getArrInfoByStop(stopId).stream()
                .map(info -> BusArrivalResponse.builder()
                        .routeId(info.routeId())
                        .arrivalMsg1(info.arrivalMsg1())
                        .predictTime1(info.kalPredictTime1())
                        .congestionNum1(info.congestionNum1())
                        .arrivalMsg2(info.arrivalMsg2())
                        .predictTime2(info.kalPredictTime2())
                        .congestionNum2(info.congestionNum2())
                        .build())
                .toList();
    }
}
