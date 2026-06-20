package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.entity.BusStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;

import java.util.List;
import java.util.Optional;

/**
 * ODSAY 식별자 → repo 매핑.
 * busLocalBlID → route_id(단건 확정), startLocalStationID → stop_id(직접 조회).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferStopResolver {

    private final BusStopRepository stopRepo;
    private final BusRouteRepository routeRepo;
    private final BusRouteStopRepository routeStopRepo;

    public record StopResolution(String stopId) {}

    public record RouteResolution(String routeId, String routeName, boolean fallback) {}

    public record SeqResolution(List<Integer> candidates) {
        public boolean isSingle() { return candidates.size() == 1; }
        public boolean isEmpty() { return candidates.isEmpty(); }
    }

    /** startLocalStationID → stop_id. empty = STOP_MAPPING_FAILED. */
    public Optional<StopResolution> resolveStop(String startLocalStationID) {
        if (startLocalStationID == null || startLocalStationID.isBlank()) return Optional.empty();
        return stopRepo.findByStopId(startLocalStationID)
                .map(BusStop::getStopId)
                .map(StopResolution::new);
    }

    /**
     * busLocalBlID → route_id(단건 확정).
     * 실패 시 busNo → findRoutesByNames fallback.
     * fallback 다건이면 empty(UNSUPPORTED_ROUTE).
     */
    public Optional<RouteResolution> resolveRoute(String busLocalBlID, String busNo) {
        // 1차: busLocalBlID 직접 조회
        if (busLocalBlID != null && !busLocalBlID.isBlank()) {
            Optional<BusRoute> route = routeRepo.findByRouteId(busLocalBlID);
            if (route.isPresent()) {
                BusRoute r = route.get();
                return Optional.of(new RouteResolution(r.getRouteId(), r.getRouteName(), false));
            }
            log.debug("[Resolver] busLocalBlID={} not found in repo, trying busNo fallback", busLocalBlID);
        }
        // fallback: busNo → findRoutesByNames
        if (busNo != null && !busNo.isBlank()) {
            List<BusRoute> routes = routeRepo.findByRouteNameIn(List.of(busNo));
            if (routes.size() == 1) {
                BusRoute r = routes.get(0);
                return Optional.of(new RouteResolution(r.getRouteId(), r.getRouteName(), true));
            }
            if (routes.size() > 1) {
                log.debug("[Resolver] busNo={} matched {} routes, cannot disambiguate", busNo, routes.size());
            }
        }
        return Optional.empty();
    }

    /**
     * routeId + stopId → seq 후보 목록.
     * 단건 = 정적 확정. 다건 = 루프노선 dedup 필요.
     */
    public SeqResolution resolveSeq(String routeId, String stopId) {
        List<Integer> seqs = routeStopRepo.findByRouteIdAndStopId(routeId, stopId)
                .stream()
                .map(BusRouteStop::getSeq)
                .distinct()
                .toList();
        return new SeqResolution(seqs);
    }
}
