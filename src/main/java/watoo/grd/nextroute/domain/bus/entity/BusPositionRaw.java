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

	/** API tmX 원본 좌표 */
	@Column(name = "tm_x")
	private Double tmX;

	/** API tmY 원본 좌표 */
	@Column(name = "tm_y")
	private Double tmY;

	/** 현재 구간 순서 */
	private Integer sectionOrder;

	/** 구간 옵셋 거리 */
	private Double sectionDistance;

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

	/** 최종 정류소 ID */
	private String lastStopId;

	/** API posX 원본 좌표 */
	@Column(name = "pos_x")
	private Double posX;

	/** API posY 원본 좌표 */
	@Column(name = "pos_y")
	private Double posY;

	/** 응답 본문에 포함된 노선 ID */
	private String apiRouteId;

	/** 차량 내부 혼잡도 */
	private Integer congestion;

	@Builder
	public BusPositionRaw(LocalDateTime collectedAt, String routeId, String vehicleId,
						  Double tmX, Double tmY, Integer sectionOrder,
						  Double sectionDistance, String stopFlag,
						  String sectionId, String dataTm, String plainNo,
						  Integer busType, String lastStopId, Double posX,
						  Double posY, String apiRouteId, Integer congestion) {
		this.collectedAt = collectedAt;
		this.routeId = routeId;
		this.vehicleId = vehicleId;
		this.tmX = tmX;
		this.tmY = tmY;
		this.sectionOrder = sectionOrder;
		this.sectionDistance = sectionDistance;
		this.stopFlag = stopFlag;
		this.sectionId = sectionId;
		this.dataTm = dataTm;
		this.plainNo = plainNo;
		this.busType = busType;
		this.lastStopId = lastStopId;
		this.posX = posX;
		this.posY = posY;
		this.apiRouteId = apiRouteId;
		this.congestion = congestion;
	}
}
