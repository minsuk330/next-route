package watoo.grd.nextroute.application.arrival.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.arrival.port.out.GyeonggiStopOrderPort;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteStopStore;

import java.util.List;
import java.util.OptionalInt;

/**
 * {@code regional_bus_route_stop.seq} 는 공급자 순번({@code stationSeq})과 같은 공간이다. 같은 노선에 같은 정류장이 두 번이면
 * 고르지 않는다 — 왕복 구간에서 다른 쪽 순번을 넣으면 반대 방향 버스의 도착을 보여 주게 된다. 서울 노선의 다중 순번과 같은 규칙이다.
 */
@Component
@RequiredArgsConstructor
public class RegionalTableGyeonggiStopOrder implements GyeonggiStopOrderPort {

    private final RegionalBusRouteStopStore store;

    @Override
    public OptionalInt orderOf(String stationId, String routeId) {
        List<Integer> orders = store.findRouteStops(RegionalBusProvider.GG, routeId).stream()
                .filter(stop -> stationId.equals(stop.stopId()))
                .map(stop -> stop.seq())
                .toList();
        return orders.size() == 1 ? OptionalInt.of(orders.get(0)) : OptionalInt.empty();
    }
}
