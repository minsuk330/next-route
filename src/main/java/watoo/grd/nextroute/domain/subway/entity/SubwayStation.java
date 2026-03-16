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

	@Column(nullable = false, unique = true)
	private String stationId;

	private String stationName;

	private String lineId;

	private String lineName;

	private Double latitude;

	private Double longitude;

	@Builder
	public SubwayStation(String stationId, String stationName, String lineId,
						 String lineName, Double latitude, Double longitude) {
		this.stationId = stationId;
		this.stationName = stationName;
		this.lineId = lineId;
		this.lineName = lineName;
		this.latitude = latitude;
		this.longitude = longitude;
	}
}
