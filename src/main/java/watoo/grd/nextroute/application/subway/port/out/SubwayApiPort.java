package watoo.grd.nextroute.application.subway.port.out;

import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayPositionFetchResult;
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

	/**
	 * 노선 하나의 실시간 열차 위치.
	 *
	 * <p>호출 축이 <b>노선</b>이라 도착정보와 분리한다(도착정보는 전 노선을 한 번에 받는다).
	 *
	 * <p>목록이 아니라 봉투를 돌려준다 — 빈 목록만으로는 "정상인데 운행 열차가 없다"와
	 * "호출이 실패했다"를 가를 수 없고, 위치 전이 추적은 그 구분에 기대기 때문이다.
	 *
	 * @param lineId 내부 line_id. 호출에 필요한 호선명 변환은 어댑터가 한다
	 */
	SubwayPositionFetchResult getRealtimePosition(String lineId);
}