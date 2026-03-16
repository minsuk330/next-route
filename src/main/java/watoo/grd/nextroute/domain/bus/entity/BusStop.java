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
/// 버스 도착 정보
public class BusStop extends BaseEntity {

	@Column(nullable = false, unique = true)
	private String stopId;

	private String stopName;

	private String arsId;

	private Double latitude;

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
