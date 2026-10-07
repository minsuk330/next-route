package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 원천 셋을 한 모양으로(#265). 서울은 기존 검색용 위치 조회({@link SearchTimeBusQueryPort#getBusPosByRtid}, 검색 몫 예산·
 * 차단기·캐시 공유)를 감싼다. 경기·TAGO 는 {@link RegionalBusLocationClient}.
 */
@Component
@RequiredArgsConstructor
public class BusPositionAdapter implements BusPositionPort {

    /** 서울 위치 조회 예산(ms). 워커 스케줄러가 부르므로 검색처럼 짧을 필요는 없다. */
    private static final long SEOUL_TIMEOUT_MS = 3_000;

    private final SearchTimeBusQueryPort seoulPort;
    private final RegionalBusLocationClient regionalClient;
    private final Clock clock;

    @Override
    public Snapshot positions(Provider provider, String routeId, String cityCode) {
        return switch (provider) {
            case SEOUL -> seoul(routeId);
            case GYEONGGI -> regionalClient.gyeonggi(routeId);
            case TAGO -> regionalClient.tago(cityCode, routeId);
        };
    }

    private Snapshot seoul(String routeId) {
        SearchTimeBusQueryPort.BusQueryResult<BusPositionInfo> result =
                seoulPort.getBusPosByRtid(routeId, SEOUL_TIMEOUT_MS);
        return toSnapshot(result, clock);
    }

    static Snapshot toSnapshot(SearchTimeBusQueryPort.BusQueryResult<BusPositionInfo> result, Clock clock) {
        if (!result.isOk()) {
            Outcome outcome = switch (result.outcome()) {
                case BLOCKED -> Outcome.BLOCKED;
                case LIMITED -> Outcome.LIMITED;
                default -> Outcome.ERROR;
            };
            return Snapshot.failed(outcome, clock.instant());
        }
        List<Vehicle> vehicles = new ArrayList<>();
        for (BusPositionInfo info : result.data()) {
            if (info.vehicleId() == null || info.sectionOrder() == null) {
                continue;
            }
            // stopFlag 1 = 그 정류소에 도착해 있다. 0 = 그 정류소를 떠나 다음 구간을 달린다.
            vehicles.add(new Vehicle(info.vehicleId(), info.plainNo(), info.sectionOrder(),
                    !"1".equals(info.stopFlag())));
        }
        return Snapshot.ok(vehicles, clock.instant());
    }
}
