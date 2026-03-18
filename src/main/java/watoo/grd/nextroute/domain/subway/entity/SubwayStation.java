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

	/** 역 고유 ID */
	@Column(nullable = false, unique = true)
	private String stationId;

	/** 역 이름 (예: "강남") */
	private String stationName;

	/** 호선 코드 (예: "1002" = 2호선) */
	private String lineId;

	/** 호선 이름 (예: "2호선") */
	private String lineName;

	/** 역 위도 (WGS84) */
	private Double latitude;

	/** 역 경도 (WGS84) */
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
