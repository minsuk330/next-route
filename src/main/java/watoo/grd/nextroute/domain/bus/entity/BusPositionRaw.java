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

	/** 다음 정류소 도착 소요시간 */
	private Integer nextStopTime;

	/** 현재 구간 순서 */
	private Integer sectionOrder;

	/** 구간 옵셋 거리 */
	private Double sectionDistance;

	/** 노선 옵셋 거리 */
	private Double routeDistance;

	/** 정차 여부 (0:운행중, 1:정차) */
	private String stopFlag;

	/** 구간 ID */
	private String sectionId;

	/** API 기준 수집 시각 */
	private String dataTm;

	/** 차량 번호판 */
	private String plainNo;

	/** 버스 타입 (0:일반, 1:저상) */
	private Integer busType;

	/** 종점 도착 소요시간 */
	private Integer lastStopTime;

	/** 최종 정류소 ID */
	private String lastStopId;

	/** API posX 원본 좌표 */
	@Column(name = "pos_x")
	private Double posX;

	/** API posY 원본 좌표 */
	@Column(name = "pos_y")
	private Double posY;

	/** 만차 여부 */
	private String isFullFlag;

	/** 막차 여부 */
	private String isLastYn;

	/** 정류소간 거리 */
	private Double fullSectionDistance;

	/** 다음 정류소 ID */
	private String nextStopId;

	/** 차량 내부 혼잡도 */
	private Integer congestion;

	/** 회차지 정류소 ID */
	private String turnStopId;

	/** WGS84 X 좌표 */
	@Column(name = "gps_x")
	private Double gpsX;

	/** WGS84 Y 좌표 */
	@Column(name = "gps_y")
	private Double gpsY;

	/** 해당 차량 운행 여부 */
	private String isRunYn;

	@Builder
	public BusPositionRaw(LocalDateTime collectedAt, String routeId, String vehicleId,
						  Integer nextStopTime, Integer sectionOrder,
						  Double sectionDistance, Double routeDistance,
						  String stopFlag, String sectionId, String dataTm,
						  String plainNo, Integer busType, Integer lastStopTime,
						  String lastStopId, Double posX, Double posY,
						  String isFullFlag, String isLastYn,
						  Double fullSectionDistance, String nextStopId,
						  Integer congestion, String turnStopId,
						  Double gpsX, Double gpsY, String isRunYn) {
		this.collectedAt = collectedAt;
		this.routeId = routeId;
		this.vehicleId = vehicleId;
		this.nextStopTime = nextStopTime;
		this.sectionOrder = sectionOrder;
		this.sectionDistance = sectionDistance;
		this.routeDistance = routeDistance;
		this.stopFlag = stopFlag;
		this.sectionId = sectionId;
		this.dataTm = dataTm;
		this.plainNo = plainNo;
		this.busType = busType;
		this.lastStopTime = lastStopTime;
		this.lastStopId = lastStopId;
		this.posX = posX;
		this.posY = posY;
		this.isFullFlag = isFullFlag;
		this.isLastYn = isLastYn;
		this.fullSectionDistance = fullSectionDistance;
		this.nextStopId = nextStopId;
		this.congestion = congestion;
		this.turnStopId = turnStopId;
		this.gpsX = gpsX;
		this.gpsY = gpsY;
		this.isRunYn = isRunYn;
	}
}
