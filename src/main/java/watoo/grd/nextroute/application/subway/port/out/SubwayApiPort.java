package watoo.grd.nextroute.application.subway.port.out;

import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.dto.SubwaySegmentInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayStationInfo;

import java.util.List;

public interface SubwayApiPort {

	List<SubwayStationInfo> getSubwayStationMaster();

	List<SubwaySegmentInfo> getStationDistance();

	List<SubwayArrivalInfo> getRealtimeArrival(String stationName);
}
