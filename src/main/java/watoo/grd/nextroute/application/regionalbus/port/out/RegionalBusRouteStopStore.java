package watoo.grd.nextroute.application.regionalbus.port.out;

import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code regional_bus_route_stop} 저장소. */
public interface RegionalBusRouteStopStore {

    /** 그 노선의 행을 통째로 바꾼다. 한 트랜잭션이라 중간에 죽어도 옛 값이 남는다. */
    void replaceRoute(RegionalBusProvider provider, String routeId, String routeNo, String cityCode,
                      List<RegionalRouteStop> stops, Instant loadedAt);

    /**
     * 노선 목록에 더는 없는 노선(폐선)을 지운다. 지운 노선 수.
     *
     * <p>기준이 "이번에 적재했는가"가 아니라 "목록에 있는가"인 이유 — 정류장이 0건이었거나 호출이 실패한
     * 노선은 옛 값을 지켜야 한다. 시각 기준이면 그 노선들까지 폐선으로 지워진다.
     */
    int deleteRoutesNotIn(Set<RouteKey> listed);

    /** 표에 있는 노선. */
    record RouteKey(RegionalBusProvider provider, String routeId) {
    }

    /** TAGO 노선 → 도시코드. 경기 노선은 빠진다(경기 실시간은 도시코드를 안 쓴다). */
    Map<String, String> tagoRouteCityCodes();

    /** 표에 있는 TAGO 노선 id 접두사(3글자). */
    Set<String> tagoRoutePrefixes();

    boolean isEmpty();
}
