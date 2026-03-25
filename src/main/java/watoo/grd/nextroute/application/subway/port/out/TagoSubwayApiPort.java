package watoo.grd.nextroute.application.subway.port.out;

import watoo.grd.nextroute.application.subway.dto.SubwayStationTagoInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayTimetableInfo;

import java.util.List;

public interface TagoSubwayApiPort {

	/** TAGO 전체 역 목록 조회 (페이지네이션 내부 처리) */
	List<SubwayStationTagoInfo> getAllStations();

	/** TAGO 역 ID + 요일 + 방향별 시간표 조회 (페이지네이션 내부 처리) */
	List<SubwayTimetableInfo> getTimetable(String tagoStationId, String dailyTypeCode, String upDownTypeCode);
}
