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

	/** 수집 시각 */
	@Column(nullable = false)
	private LocalDateTime collectedAt;

	/** 노선 ID */
	private String routeId;

	/** 정류소 ID */
	private String stopId;

	/** 정류소 순번 */
	private Integer seq;

	// ----- 첫 번째 도착 예정 버스 -----

	/** 도착 예상 시간 (초) */
	private Integer predictTime1;

	/** 직전 구간 소요시간 (초) */
	private Integer sectionTime1;

	/** 직전 구간 평균 속도 (km/h) */
	private Double sectionSpeed1;

	/** 도착 여부 ("0":미도착, "1":도착) */
	private String isArrive1;

	/** 차량 ID */
	private String vehicleId1;

	/** 차량 번호판 */
	private String plateNo1;

	/** 도착 안내 메시지 1 */
	private String arrivalMsg1;

	/** 구간 순서 1 */
	private Integer sectionOrder1;

	/** 현재 위치 정류소명 1 */
	private String stationName1;

	// ----- 두 번째 도착 예정 버스 -----

	/** 도착 예상 시간 (초) */
	private Integer predictTime2;

	/** 직전 구간 소요시간 (초) */
	private Integer sectionTime2;

	/** 직전 구간 평균 속도 (km/h) */
	private Double sectionSpeed2;

	/** 도착 여부 ("0":미도착, "1":도착) */
	private String isArrive2;

	/** 차량 ID */
	private String vehicleId2;

	/** 차량 번호판 */
	private String plateNo2;

	/** 도착 안내 메시지 2 */
	private String arrivalMsg2;

	/** 구간 순서 2 */
	private Integer sectionOrder2;

	/** 현재 위치 정류소명 2 */
	private String stationName2;

	@Builder
	public BusArrivalRaw(LocalDateTime collectedAt, String routeId, String stopId, Integer seq,
						 Integer predictTime1, Integer sectionTime1, Double sectionSpeed1,
						 String isArrive1, String vehicleId1, String plateNo1,
						 String arrivalMsg1, Integer sectionOrder1, String stationName1,
						 Integer predictTime2, Integer sectionTime2, Double sectionSpeed2,
						 String isArrive2, String vehicleId2, String plateNo2,
						 String arrivalMsg2, Integer sectionOrder2, String stationName2) {
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
		this.arrivalMsg1 = arrivalMsg1;
		this.sectionOrder1 = sectionOrder1;
		this.stationName1 = stationName1;
		this.predictTime2 = predictTime2;
		this.sectionTime2 = sectionTime2;
		this.sectionSpeed2 = sectionSpeed2;
		this.isArrive2 = isArrive2;
		this.vehicleId2 = vehicleId2;
		this.plateNo2 = plateNo2;
		this.arrivalMsg2 = arrivalMsg2;
		this.sectionOrder2 = sectionOrder2;
		this.stationName2 = stationName2;
	}
}
