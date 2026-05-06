package watoo.grd.nextroute.application.nearby.port.in;

import watoo.grd.nextroute.application.nearby.dto.NearbySubwayStationResult;

import java.util.List;

public interface GetNearbySubwayStationsUseCase {
    List<NearbySubwayStationResult> getNearbySubwayStations(double lat, double lng, int limit);
}
