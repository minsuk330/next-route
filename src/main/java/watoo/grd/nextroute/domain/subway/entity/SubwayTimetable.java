package watoo.grd.nextroute.domain.subway.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

@Entity
@Table(name = "subway_timetable")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubwayTimetable extends BaseEntity {

	/** TAGO 역 ID (SubwayStationTago 참조) */
	private String tagoStationId;

	/** 역명 (비정규화, 조회 편의) */
	private String stationName;

	/** 호선 코드 (매칭된 경우) */
	private String lineId;

	/** 상행(U) / 하행(D) */
	private String direction;

	/** 평일(01) / 토요일(02) / 일요일(03) */
	private String dayType;

	/** 출발시각 (HHmmss) */
	private String depTime;

	/** 도착시각 (HHmmss, 출발역이면 "0") */
	private String arrTime;

	/** 종착역명 (행선지) */
	private String endStationName;

	/** TAGO 노선 ID (예: "MTRARA1") */
	private String subwayRouteId;

	@Builder
	public SubwayTimetable(String tagoStationId, String stationName, String lineId,
						   String direction, String dayType, String depTime,
						   String arrTime, String endStationName, String subwayRouteId) {
		this.tagoStationId = tagoStationId;
		this.stationName = stationName;
		this.lineId = lineId;
		this.direction = direction;
		this.dayType = dayType;
		this.depTime = depTime;
		this.arrTime = arrTime;
		this.endStationName = endStationName;
		this.subwayRouteId = subwayRouteId;
	}
}
