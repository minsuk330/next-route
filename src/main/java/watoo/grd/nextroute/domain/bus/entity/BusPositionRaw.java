package watoo.grd.nextroute.domain.bus.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.LocalDateTime;

@Entity
@Table(name = "bus_position_raw")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusPositionRaw extends BaseEntity {

	@Column(nullable = false)
	private LocalDateTime collectedAt;

	private String routeId;

	private String vehicleId;

	private Double latitude;

	private Double longitude;

	private Integer stopSeq;

	private Double sectionSpeed;

	private Integer sectionOrder;

	@Builder
	public BusPositionRaw(LocalDateTime collectedAt, String routeId, String vehicleId,
						  Double latitude, Double longitude, Integer stopSeq,
						  Double sectionSpeed, Integer sectionOrder) {
		this.collectedAt = collectedAt;
		this.routeId = routeId;
		this.vehicleId = vehicleId;
		this.latitude = latitude;
		this.longitude = longitude;
		this.stopSeq = stopSeq;
		this.sectionSpeed = sectionSpeed;
		this.sectionOrder = sectionOrder;
	}
}
