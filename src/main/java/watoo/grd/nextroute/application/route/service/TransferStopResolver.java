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
import java.util.regex.Pattern;

/**
 * ODSAY 식별자 → repo 매핑.
 * busLocalBlID → route_id(단건 확정), startLocalStationID → stop_id(직접 조회).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferStopResolver {

    /**
     * 다른 지역 노선으로 <b>확인되는</b> id: 경기 GBIS(2로 시작하는 9자리), TAGO(영문 3글자 + 숫자·하이픈).
     * 서울 노선 id 는 1로 시작해 겹치지 않는다. 모양을 모르는 id(ODsay 내부 busID 등)는 여기 걸리지 않는다.
     */
    private static final Pattern OTHER_REGION_ROUTE_ID = Pattern.compile("^(?:2\\d{8}|[A-Z]{3}[\\d-]+)$");

    private final BusStopRepository stopRepo;
    private final BusRouteRepository routeRepo;
    private final BusRouteStopRepository routeStopRepo;

    public record StopResolution(String stopId) {}

    /** {@code termMinutes} = 배차간격(분). STATIC 대기 근사(term/2)용. 미확보 시 null. */
    public record RouteResolution(String routeId, String routeName, boolean fallback, Integer termMinutes) {}

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
     * 실패 시 busNo → findRoutesByNames fallback — 단 busLocalBlID 가 다른 지역(경기·TAGO) 노선 id 면 안 한다.
     * fallback 다건이면 empty(UNSUPPORTED_ROUTE).
     */
    public Optional<RouteResolution> resolveRoute(String busLocalBlID, String busNo) {
        // 1차: busLocalBlID 직접 조회
        if (busLocalBlID != null && !busLocalBlID.isBlank()) {
            Optional<BusRoute> route = routeRepo.findByRouteId(busLocalBlID);
            if (route.isPresent()) {
                BusRoute r = route.get();
                return Optional.of(new RouteResolution(r.getRouteId(), r.getRouteName(), false, r.getTerm()));
            }
            // 다른 지역 노선으로 확인되면 번호만 같은 서울 노선으로 둔갑시키지 않는다 — 충주 103번이 서울 103번
            // (100100008)으로 확정된 사례. 모양을 모르는 id 는 막지 않는다: ODsay 경로는 busLocalBlID 가 비면 내부
            // busID 를 넘기는데(JourneyCompiler.sourceRouteId), 그때는 번호 폴백이 유일한 해석 수단이다.
            if (OTHER_REGION_ROUTE_ID.matcher(busLocalBlID).matches()) {
                log.debug("[Resolver] busLocalBlID={} is another region's route id; skip busNo fallback", busLocalBlID);
                return Optional.empty();
            }
            log.debug("[Resolver] busLocalBlID={} not found in repo, trying busNo fallback", busLocalBlID);
        }
        // fallback: busNo → findRoutesByNames
        if (busNo != null && !busNo.isBlank()) {
            List<BusRoute> routes = routeRepo.findByRouteNameIn(List.of(busNo));
            if (routes.size() == 1) {
                BusRoute r = routes.get(0);
                return Optional.of(new RouteResolution(r.getRouteId(), r.getRouteName(), true, r.getTerm()));
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
