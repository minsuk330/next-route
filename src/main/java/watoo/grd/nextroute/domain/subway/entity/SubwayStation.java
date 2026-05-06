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
@Table(name = "subway_station")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubwayStation extends BaseEntity {

	@Column(name = "statn_id")
	private String stationId;

	@Column(name = "tago_station_id")
	private String tagoStationId;

	@Column(name = "tago_mapping_status")
	private String tagoMappingStatus;

	@Column(name = "statn_nm")
	private String stationName;

	@Column(name = "line_id")
	private String lineId;

	@Column(name = "search_line_name")
	private String lineName;

	@Column(name = "kakao_query")
	private String kakaoQuery;

	private Double latitude;

	private Double longitude;

	@Builder
	public SubwayStation(String stationId, String tagoStationId, String stationName, String lineId,
						 String lineName, String kakaoQuery, Double latitude, Double longitude) {
		this.stationId = stationId;
		this.tagoStationId = tagoStationId;
		this.stationName = stationName;
		this.lineId = lineId;
		this.lineName = lineName;
		this.kakaoQuery = kakaoQuery;
		this.latitude = latitude;
		this.longitude = longitude;
	}

	public void updateCoordinates(double latitude, double longitude) {
		this.latitude = latitude;
		this.longitude = longitude;
	}
}
