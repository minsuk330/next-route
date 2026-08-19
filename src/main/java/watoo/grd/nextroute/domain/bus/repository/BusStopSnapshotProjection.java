package watoo.grd.nextroute.domain.bus.repository;

/** 지도 스냅샷용 정류장 한 행. 거리 계산이 없어 {@link NearbyBusStopProjection} 와 따로 둔다. */
public interface BusStopSnapshotProjection {
    String getStopId();
    String getStopName();
    String getArsId();
    Double getLatitude();
    Double getLongitude();
}
