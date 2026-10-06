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

    /** 서울 버스 노선 id 모양. 경기(2로 시작)·TAGO(영문 접두사)와 겹치지 않는다. */
    private static final Pattern SEOUL_ROUTE_ID = Pattern.compile("^1\\d{8}$");

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
     * 실패 시 busNo → findRoutesByNames fallback — 단 busLocalBlID 가 없거나 서울 노선 id 모양일 때만.
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
            // 서울 노선 id 모양(1로 시작하는 9자리)이 아니면 다른 지역 노선이다(경기 2xxxxxxxx, TAGO 영문 접두사).
            // 번호만 같은 서울 노선으로 둔갑시키지 않는다 — 충주 103번이 서울 103번(100100008)으로 확정된 사례.
            if (!SEOUL_ROUTE_ID.matcher(busLocalBlID).matches()) {
                log.debug("[Resolver] busLocalBlID={} is not a Seoul route id; skip busNo fallback", busLocalBlID);
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
