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
@Table(name = "bus_arrival_raw")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BusArrivalRaw extends BaseEntity {

	@Column(nullable = false)
	private LocalDateTime collectedAt;

	private String routeId;

	private String stopId;

	private Integer seq;

	private Integer predictTime1;

	private Integer sectionTime1;

	private Double sectionSpeed1;

	private String isArrive1;

	private String vehicleId1;

	private String plateNo1;

	private Integer predictTime2;

	private Integer sectionTime2;

	private Double sectionSpeed2;

	private String isArrive2;

	private String vehicleId2;

	private String plateNo2;

	@Builder
	public BusArrivalRaw(LocalDateTime collectedAt, String routeId, String stopId, Integer seq,
						 Integer predictTime1, Integer sectionTime1, Double sectionSpeed1,
						 String isArrive1, String vehicleId1, String plateNo1,
						 Integer predictTime2, Integer sectionTime2, Double sectionSpeed2,
						 String isArrive2, String vehicleId2, String plateNo2) {
		this.collectedAt = collectedAt;
		this.routeId = routeId;
		this.stopId = stopId;
		this.seq = seq;
		this.predictTime1 = predictTime1;
		this.sectionTime1 = sectionTime1;
		this.sectionSpeed1 = sectionSpeed1;
		this.isArrive1 = isArrive1;
		this.vehicleId1 = vehicleId1;
		this.plateNo1 = plateNo1;
		this.predictTime2 = predictTime2;
		this.sectionTime2 = sectionTime2;
		this.sectionSpeed2 = sectionSpeed2;
		this.isArrive2 = isArrive2;
		this.vehicleId2 = vehicleId2;
		this.plateNo2 = plateNo2;
	}
}
