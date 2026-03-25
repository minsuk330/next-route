package watoo.grd.nextroute.domain.subway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

@Entity
@Table(name = "subway_station_tago")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubwayStationTago extends BaseEntity {

	/** TAGO 역 ID (예: "MTRS11150") */
	@Column(nullable = false, unique = true)
	private String tagoStationId;

	/** 역명 (예: "서울역") */
	private String stationName;

	/** TAGO 노선명 (예: "1호선") */
	private String routeName;

	/** 기존 SubwayStation.stationId (매칭된 경우, nullable) */
	private String stationId;

	/** 기존 SubwayStation.lineId (매칭된 경우, nullable) */
	private String lineId;

	@Builder
	public SubwayStationTago(String tagoStationId, String stationName, String routeName,
							 String stationId, String lineId) {
		this.tagoStationId = tagoStationId;
		this.stationName = stationName;
		this.routeName = routeName;
		this.stationId = stationId;
		this.lineId = lineId;
	}
}
