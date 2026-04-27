package watoo.grd.nextroute.application.subway.port.in;

import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeResponse;

import java.util.List;

public interface SubwayRealtimeQueryUseCase {
    SubwayRealtimeResponse query(String lineId, List<String> stationNames, String direction);
}
