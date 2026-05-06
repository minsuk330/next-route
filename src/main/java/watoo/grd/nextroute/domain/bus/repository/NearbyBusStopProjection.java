package watoo.grd.nextroute.domain.bus.repository;

public interface NearbyBusStopProjection {
    String getStopId();
    String getStopName();
    String getArsId();
    Double getLatitude();
    Double getLongitude();
    Double getDistMeters();
}
