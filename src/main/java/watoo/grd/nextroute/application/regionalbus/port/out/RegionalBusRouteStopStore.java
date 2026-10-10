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

    /**
     * 정류장 ID(경기 GBIS stationId, TAGO nodeid) → 정류장 번호(#266). 번호가 없거나 노선마다 엇갈리면 빠진다.
     */
    Map<String, String> stopNumbers(java.util.Collection<String> stopIds);

    /** 번호가 하나라도 적재돼 있는지. V64 직후 1회 재적재 판단용. */
    boolean hasStopNumbers();

    /**
     * 노선 하나의 정류장 순서(실시간 순번 오름차순). 재탐색 보드의 경기·TAGO 중간 하차 기점(#264 PR-4)이
     * 내 위치와 앞으로 지날 정류장을 이 순번 공간에서 잰다. 모르는 노선이면 빈 목록.
     */
    java.util.List<RegionalRouteStop> findRouteStops(RegionalBusProvider provider, String routeId);
}
