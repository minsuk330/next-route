package watoo.grd.nextroute.domain.subway.repository;

public interface NearbySubwayStationProjection {
    String getStationId();
    String getStationName();
    String getLineId();
    String getLineName();
    Double getLatitude();
    Double getLongitude();
    Double getDistMeters();
}
