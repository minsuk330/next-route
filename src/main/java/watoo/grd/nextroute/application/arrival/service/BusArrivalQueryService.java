package watoo.grd.nextroute.application.arrival.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.arrival.dto.BusArrivalResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetBusArrivalUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BusArrivalQueryService implements GetBusArrivalUseCase {

    private final BusApiPort busApiPort;
    private final BusDataService busDataService;

    @Override
    public List<BusArrivalResponse> getArrivals(String stopId, String routeId) {
        // (stopId, routeId) → 경유순번(seq). Seoul getArrInfoByRoute는 ord 필수.
        List<BusRouteStop> mappings = busDataService.findBusRouteByStopAndRoute(stopId, routeId);
        if (mappings.size() != 1) {
            // 매핑 없음 또는 루프노선 다중순번(모호) → 빈 결과.
            return List.of();
        }
        Integer ord = mappings.get(0).getSeq();
        if (ord == null) {
            return List.of();
        }

        return busApiPort.getArrInfoByStop(stopId, routeId, String.valueOf(ord)).stream()
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
