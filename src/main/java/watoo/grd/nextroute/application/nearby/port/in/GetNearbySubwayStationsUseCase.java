package watoo.grd.nextroute.application.nearby.port.in;

import watoo.grd.nextroute.application.nearby.dto.NearbySubwayStationResult;

import java.util.List;

public interface GetNearbySubwayStationsUseCase {
    List<NearbySubwayStationResult> getNearbySubwayStations(double lat, double lng, int limit);

    /** {@code includeRegional} 이면 지방 지하철역(시간표 기준)을 섞고 항목마다 {@code arrivalSource} 를 싣는다. */
    default List<NearbySubwayStationResult> getNearbySubwayStations(double lat, double lng, int limit, boolean includeRegional) {
        return getNearbySubwayStations(lat, lng, limit);
    }
}
