package watoo.grd.nextroute.testsupport;

import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteStopStore;
import watoo.grd.nextroute.application.route.mapping.TagoCityCodeLookup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** {@code regional_bus_route_stop} 의 메모리 대역. 노선 단위로 통째 교체하는 계약만 흉내 낸다. */
public class InMemoryRegionalBusRouteStopStore implements RegionalBusRouteStopStore {

    public record Row(String routeNo, String cityCode, List<RegionalRouteStop> stops, Instant loadedAt) {
    }

    public final Map<RouteKey, Row> routes = new LinkedHashMap<>();

    /** 표가 비어 있는 조회기 — 접두사 표 폴백만 쓴다(배포 직후·V56 미배치와 같은 동작). */
    public static TagoCityCodeLookup prefixTableOnlyLookup() {
        TagoCityCodeLookup lookup = new TagoCityCodeLookup(new InMemoryRegionalBusRouteStopStore());
        lookup.refresh();
        return lookup;
    }

    @Override
    public synchronized void replaceRoute(RegionalBusProvider provider, String routeId, String routeNo,
                                          String cityCode, List<RegionalRouteStop> stops, Instant loadedAt) {
        routes.put(new RouteKey(provider, routeId), new Row(routeNo, cityCode, List.copyOf(stops), loadedAt));
    }

    @Override
    public synchronized int deleteRoutesNotIn(Set<RouteKey> listed) {
        List<RouteKey> stale = new ArrayList<>(routes.keySet());
        stale.removeAll(listed);
        stale.forEach(routes::remove);
        return stale.size();
    }

    @Override
    public synchronized Map<String, String> tagoRouteCityCodes() {
        Map<String, String> out = new HashMap<>();
        routes.forEach((key, row) -> {
            if (key.provider() == RegionalBusProvider.TAGO && row.cityCode() != null) {
                out.put(key.routeId(), row.cityCode());
            }
        });
        return out;
    }

    @Override
    public synchronized Set<String> tagoRoutePrefixes() {
        return routes.keySet().stream()
                .filter(key -> key.provider() == RegionalBusProvider.TAGO)
                .map(key -> key.routeId().substring(0, 3))
                .collect(Collectors.toSet());
    }

    @Override
    public synchronized Map<String, String> stopNumbers(java.util.Collection<String> stopIds) {
        Map<String, String> out = new LinkedHashMap<>();
        java.util.Set<String> conflicting = new java.util.HashSet<>();
        routes.values().forEach(row -> row.stops().forEach(stop -> {
            if (stopIds.contains(stop.stopId()) && stop.stopNo() != null && !stop.stopNo().isBlank()) {
                String prev = out.putIfAbsent(stop.stopId(), stop.stopNo().trim());
                if (prev != null && !prev.equals(stop.stopNo().trim())) {
                    conflicting.add(stop.stopId());
                }
            }
        }));
        conflicting.forEach(out::remove);
        return out;
    }

    @Override
    public synchronized boolean hasStopNumbers() {
        return routes.values().stream().flatMap(row -> row.stops().stream()).anyMatch(stop -> stop.stopNo() != null);
    }

    @Override
    public synchronized boolean isEmpty() {
        return routes.isEmpty();
    }

    @Override
    public synchronized List<RegionalRouteStop> findRouteStops(RegionalBusProvider provider, String routeId) {
        Row row = routes.get(new RouteKey(provider, routeId));
        return row == null ? List.of() : row.stops().stream()
                .sorted(java.util.Comparator.comparingInt(RegionalRouteStop::seq))
                .toList();
    }
}
