package watoo.grd.nextroute.domain.bus.repository;

/** 노선 경유 정류장 조회용 projection (bus_route_stop ⋈ bus_stop). 좌표는 nullable. */
public interface RouteStopProjection {
    Integer getSeq();
    String getStopId();
    String getStopName();
    Double getLatitude();
    Double getLongitude();
    String getDirection();
}
