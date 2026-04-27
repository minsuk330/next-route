package watoo.grd.nextroute.application.subway.port.out;

import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.dto.SubwaySegmentInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayStationInfo;

import java.util.List;

public interface SubwayApiPort {

	List<SubwayStationInfo> getSubwayStationMaster();

	List<SubwaySegmentInfo> getStationDistance();

  /// 모든 도착정보 전부 조회
	List<SubwayArrivalInfo> getRealtimeArrival();
  /// 각 역에 따른 도착정보 조회
	List<SubwayArrivalInfo> getRealtimeArrivalByStation(String stationName);
}