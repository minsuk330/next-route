package watoo.grd.nextroute.domain.bus.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

@Entity
@Table(name = "bus_stop")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
/// 버스 정류소
public class BusStop extends BaseEntity {

	/** 정류소 고유 ID (노드 ID) */
	@Column(nullable = false, unique = true,name = "stop_id")
	private String stopId;

	/** 정류소 이름 (예: "강남역") */
	private String stopName;

	/** 정류소 고유번호 (안내단말기 ID, 예: "23614") */
	private String arsId;

	/** 위도 (WGS84) */
	private Double latitude;

	/** 경도 (WGS84) */
	private Double longitude;

	@Builder
	public BusStop(String stopId, String stopName, String arsId,
				   Double latitude, Double longitude) {
		this.stopId = stopId;
		this.stopName = stopName;
		this.arsId = arsId;
		this.latitude = latitude;
		this.longitude = longitude;
	}
}
