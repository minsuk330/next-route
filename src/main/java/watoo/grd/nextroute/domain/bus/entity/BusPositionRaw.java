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

	/** 수집 시각 */
	@Column(nullable = false)
	private LocalDateTime collectedAt;

	/** 노선 ID */
	private String routeId;

	/** 차량 ID */
	private String vehicleId;

	/** 차량 위도 (WGS84) */
	private Double latitude;

	/** 차량 경도 (WGS84) */
	private Double longitude;

	/** 최근 정차 정류소 순번 */
	private Integer stopSeq;

	/** 현재 구간 속도 (km/h) */
	private Double sectionSpeed;

	/** 현재 구간 순서 */
	private Integer sectionOrder;

	/** 정차 여부 (0:운행중, 1:정차) */
	private String stopFlag;

	/** API 기준 수집 시각 */
	private String dataTm;

	/** 차량 번호판 */
	private String plainNo;

	/** 버스 타입 (0:일반, 1:저상) */
	private Integer busType;

	/** 최종 정류소 ID */
	private String lastStopId;

	/** 운행 여부 */
	private String isRunning;

	@Builder
	public BusPositionRaw(LocalDateTime collectedAt, String routeId, String vehicleId,
						  Double latitude, Double longitude, Integer stopSeq,
						  Double sectionSpeed, Integer sectionOrder,
						  String stopFlag, String dataTm, String plainNo,
						  Integer busType, String lastStopId, String isRunning) {
		this.collectedAt = collectedAt;
		this.routeId = routeId;
		this.vehicleId = vehicleId;
		this.latitude = latitude;
		this.longitude = longitude;
		this.stopSeq = stopSeq;
		this.sectionSpeed = sectionSpeed;
		this.sectionOrder = sectionOrder;
		this.stopFlag = stopFlag;
		this.dataTm = dataTm;
		this.plainNo = plainNo;
		this.busType = busType;
		this.lastStopId = lastStopId;
		this.isRunning = isRunning;
	}
}
