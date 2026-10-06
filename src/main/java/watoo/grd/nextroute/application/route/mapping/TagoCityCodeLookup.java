package watoo.grd.nextroute.application.route.mapping;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteStopStore;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * TAGO 도시코드 조회(#267). 노선 → 도시코드를 {@code regional_bus_route_stop} 에서 읽고, 없으면
 * {@link TagoCityCodes}(접두사 표)로 폴백한다.
 *
 * <p>접두사 표만으로는 안 되는 이유: 접두사 하나가 여러 시군에 걸친다(TSB 49곳, ADB·YDB·DJB·WJB 등).
 * 노선은 도시 하나에만 속한다(2026-10-06 실측, 21,393노선 중복 0).
 *
 * <p>표는 메모리 스냅샷으로 들고 있다(TAGO 노선 약 1.9만). 부팅 직후, 6시간마다, 적재 직후에 새로 읽는다.
 * api 는 적재를 하지 않으므로 주기 갱신으로 따라간다. 표가 없거나(V56 미배치) 비어 있으면 폴백만 쓴다 —
 * 지금과 같은 동작이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TagoCityCodeLookup {

    private final RegionalBusRouteStopStore store;

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    private record Snapshot(Map<String, String> routeCityCodes, Set<String> prefixes) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Set.of());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        refresh();
    }

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT6H")
    public void refresh() {
        try {
            Map<String, String> routes = Map.copyOf(store.tagoRouteCityCodes());
            Set<String> prefixes = Set.copyOf(store.tagoRoutePrefixes());
            snapshot = new Snapshot(routes, prefixes);
            log.info("[TagoCityCodeLookup] refreshed routes={} prefixes={}", routes.size(), prefixes.size());
        } catch (RuntimeException e) {
            // 표가 아직 없거나(V56 미배치) DB 가 잠깐 안 될 때. 쥐고 있던 스냅샷을 그대로 쓴다.
            log.warn("[TagoCityCodeLookup] refresh failed, keep previous: {}", e.getMessage());
        }
    }

    /** 노선의 도시코드. 표 → 접두사 표 순서. */
    public Optional<String> cityCodeOfRoute(String routeId) {
        if (routeId == null) {
            return Optional.empty();
        }
        String city = snapshot.routeCityCodes().get(routeId);
        if (city != null) {
            return Optional.of(city);
        }
        if (routeId.length() < 3) {
            return Optional.empty();
        }
        return TagoCityCodes.cityCodeOf(routeId.substring(0, 3));
    }

    /**
     * 이 접두사의 정류장에 실시간 도착을 물을 수 있는가. 정류장만 보고 정하는 자리(노선을 모름)용이라
     * 도시까지는 못 정한다 — 실제 호출은 노선으로 도시를 찾는다.
     */
    public boolean isRealtimePrefix(String prefix) {
        return prefix != null
                && (snapshot.prefixes().contains(prefix) || TagoCityCodes.cityCodeOf(prefix).isPresent());
    }
}
