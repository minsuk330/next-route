package watoo.grd.nextroute.application.subway.port.out;

import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;

import java.util.List;

public interface SubwayApiPort {

	List<SubwayArrivalInfo> getRealtimeArrival(String stationName);
}
