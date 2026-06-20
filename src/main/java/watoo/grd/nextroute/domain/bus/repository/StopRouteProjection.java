package watoo.grd.nextroute.domain.bus.repository;

/** 정류장 경유 노선 조회용 projection (bus_route_stop ⋈ bus_route). */
public interface StopRouteProjection {
    String getRouteId();
    String getRouteName();
    String getDirection();
    Integer getRouteType();
    String getStartStation();
    String getEndStation();
}
