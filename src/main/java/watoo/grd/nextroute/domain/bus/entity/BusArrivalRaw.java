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

	// ===== 공통 =====

	@Column(nullable = false)
	private LocalDateTime collectedAt;

	private String routeId;
	private String stopId;
	private String arsId;
	private Integer seq;
	private String direction;
	private Integer routeType;
	private Integer term;
	private String dataTimestamp;
	private String detourYn;
	private String nextBusYn;

	// ===== 첫 번째 도착예정 버스 =====

	private String arrivalMsg1;
	private String vehicleId1;
	private String plateNo1;
	private Integer busType1;
	private Integer sectionOrder1;
	private String stationName1;
	private String isArrive1;
	private String isLast1;
	private String isFull1;

	/** 지수평활 도착예정시간(초) */
	private Integer predictTime1;
	/** 기타1 도착예정시간(초) */
	private Integer kalPredictTime1;
	/** 기타2 도착예정시간(초) */
	private Integer neuPredictTime1;
	/** 종점 도착예정시간(초) */
	private Integer goalTime1;

	/** 이동평균 보정계수 */
	private Double avgCoefficient1;
	/** 지수평활 보정계수 */
	private Double expCoefficient1;
	/** 기타1 보정계수 */
	private Double kalCoefficient1;
	/** 기타2 보정계수 */
	private Double neuCoefficient1;

	/** 여행시간(분) */
	private Integer sectionTime1;
	/** 여행속도(km/h) */
	private Double sectionSpeed1;

	/** 혼잡도 - 뒷차 인원/혼잡도 */
	private Integer congestionNum1;
	/** 혼잡도 - 뒷차 구분 (0:없음, 2:재차인원, 4:혼잡도) */
	private Integer congestionDiv1;
	/** 혼잡도 - 재차 인원/혼잡도 */
	private Integer rideNum1;
	/** 혼잡도 - 재차 구분 */
	private Integer rideDiv1;

	/** 다음 정류소 ID */
	private String nextStopId1;
	/** 다음 정류소 순번 */
	private Integer nextStopOrd1;
	/** 다음 정류소 예정여행시간(초) */
	private Integer nextStopSec1;
	/** 다음 정류소 예정여행속도 */
	private Integer nextStopSpd1;

	/** 1번째 주요정류소 순번 */
	private Integer mainStopOrd1;
	/** 1번째 주요정류소 예정여행시간(초) */
	private Integer mainStopSec1;
	/** 1번째 주요정류소 ID */
	private String mainStopId1;

	/** 2번째 주요정류소 순번 */
	private Integer main2StopOrd1;
	/** 2번째 주요정류소 예정여행시간(초) */
	private Integer main2StopSec1;
	/** 2번째 주요정류소 ID */
	private String main2StopId1;

	/** 3번째 주요정류소 순번 */
	private Integer main3StopOrd1;
	/** 3번째 주요정류소 예정여행시간(초) */
	private Integer main3StopSec1;
	/** 3번째 주요정류소 ID */
	private String main3StopId1;

	// ===== 두 번째 도착예정 버스 =====

	private String arrivalMsg2;
	private String vehicleId2;
	private String plateNo2;
	private Integer busType2;
	private Integer sectionOrder2;
	private String stationName2;
	private String isArrive2;
	private String isLast2;
	private String isFull2;

	private Integer predictTime2;
	private Integer kalPredictTime2;
	private Integer neuPredictTime2;
	private Integer goalTime2;

	private Double avgCoefficient2;
	private Double expCoefficient2;
	private Double kalCoefficient2;
	private Double neuCoefficient2;

	private Integer sectionTime2;
	private Double sectionSpeed2;

	private Integer congestionNum2;
	private Integer congestionDiv2;
	private Integer rideNum2;
	private Integer rideDiv2;

	private String nextStopId2;
	private Integer nextStopOrd2;
	private Integer nextStopSec2;
	private Integer nextStopSpd2;

	private Integer mainStopOrd2;
	private Integer mainStopSec2;
	private String mainStopId2;

	private Integer main2StopOrd2;
	private Integer main2StopSec2;
	private String main2StopId2;

	private Integer main3StopOrd2;
	private Integer main3StopSec2;
	private String main3StopId2;

	@Builder
	public BusArrivalRaw(
			LocalDateTime collectedAt, String routeId, String stopId, String arsId,
			Integer seq, String direction, Integer routeType, Integer term,
			String dataTimestamp, String detourYn, String nextBusYn,
			// 첫 번째 버스
			String arrivalMsg1, String vehicleId1, String plateNo1, Integer busType1,
			Integer sectionOrder1, String stationName1, String isArrive1, String isLast1, String isFull1,
			Integer predictTime1, Integer kalPredictTime1, Integer neuPredictTime1, Integer goalTime1,
			Double avgCoefficient1, Double expCoefficient1, Double kalCoefficient1, Double neuCoefficient1,
			Integer sectionTime1, Double sectionSpeed1,
			Integer congestionNum1, Integer congestionDiv1, Integer rideNum1, Integer rideDiv1,
			String nextStopId1, Integer nextStopOrd1, Integer nextStopSec1, Integer nextStopSpd1,
			Integer mainStopOrd1, Integer mainStopSec1, String mainStopId1,
			Integer main2StopOrd1, Integer main2StopSec1, String main2StopId1,
			Integer main3StopOrd1, Integer main3StopSec1, String main3StopId1,
			// 두 번째 버스
			String arrivalMsg2, String vehicleId2, String plateNo2, Integer busType2,
			Integer sectionOrder2, String stationName2, String isArrive2, String isLast2, String isFull2,
			Integer predictTime2, Integer kalPredictTime2, Integer neuPredictTime2, Integer goalTime2,
			Double avgCoefficient2, Double expCoefficient2, Double kalCoefficient2, Double neuCoefficient2,
			Integer sectionTime2, Double sectionSpeed2,
			Integer congestionNum2, Integer congestionDiv2, Integer rideNum2, Integer rideDiv2,
			String nextStopId2, Integer nextStopOrd2, Integer nextStopSec2, Integer nextStopSpd2,
			Integer mainStopOrd2, Integer mainStopSec2, String mainStopId2,
			Integer main2StopOrd2, Integer main2StopSec2, String main2StopId2,
			Integer main3StopOrd2, Integer main3StopSec2, String main3StopId2) {
		this.collectedAt = collectedAt;
		this.routeId = routeId;
		this.stopId = stopId;
		this.arsId = arsId;
		this.seq = seq;
		this.direction = direction;
		this.routeType = routeType;
		this.term = term;
		this.dataTimestamp = dataTimestamp;
		this.detourYn = detourYn;
		this.nextBusYn = nextBusYn;
		// 첫 번째 버스
		this.arrivalMsg1 = arrivalMsg1;
		this.vehicleId1 = vehicleId1;
		this.plateNo1 = plateNo1;
		this.busType1 = busType1;
		this.sectionOrder1 = sectionOrder1;
		this.stationName1 = stationName1;
		this.isArrive1 = isArrive1;
		this.isLast1 = isLast1;
		this.isFull1 = isFull1;
		this.predictTime1 = predictTime1;
		this.kalPredictTime1 = kalPredictTime1;
		this.neuPredictTime1 = neuPredictTime1;
		this.goalTime1 = goalTime1;
		this.avgCoefficient1 = avgCoefficient1;
		this.expCoefficient1 = expCoefficient1;
		this.kalCoefficient1 = kalCoefficient1;
		this.neuCoefficient1 = neuCoefficient1;
		this.sectionTime1 = sectionTime1;
		this.sectionSpeed1 = sectionSpeed1;
		this.congestionNum1 = congestionNum1;
		this.congestionDiv1 = congestionDiv1;
		this.rideNum1 = rideNum1;
		this.rideDiv1 = rideDiv1;
		this.nextStopId1 = nextStopId1;
		this.nextStopOrd1 = nextStopOrd1;
		this.nextStopSec1 = nextStopSec1;
		this.nextStopSpd1 = nextStopSpd1;
		this.mainStopOrd1 = mainStopOrd1;
		this.mainStopSec1 = mainStopSec1;
		this.mainStopId1 = mainStopId1;
		this.main2StopOrd1 = main2StopOrd1;
		this.main2StopSec1 = main2StopSec1;
		this.main2StopId1 = main2StopId1;
		this.main3StopOrd1 = main3StopOrd1;
		this.main3StopSec1 = main3StopSec1;
		this.main3StopId1 = main3StopId1;
		// 두 번째 버스
		this.arrivalMsg2 = arrivalMsg2;
		this.vehicleId2 = vehicleId2;
		this.plateNo2 = plateNo2;
		this.busType2 = busType2;
		this.sectionOrder2 = sectionOrder2;
		this.stationName2 = stationName2;
		this.isArrive2 = isArrive2;
		this.isLast2 = isLast2;
		this.isFull2 = isFull2;
		this.predictTime2 = predictTime2;
		this.kalPredictTime2 = kalPredictTime2;
		this.neuPredictTime2 = neuPredictTime2;
		this.goalTime2 = goalTime2;
		this.avgCoefficient2 = avgCoefficient2;
		this.expCoefficient2 = expCoefficient2;
		this.kalCoefficient2 = kalCoefficient2;
		this.neuCoefficient2 = neuCoefficient2;
		this.sectionTime2 = sectionTime2;
		this.sectionSpeed2 = sectionSpeed2;
		this.congestionNum2 = congestionNum2;
		this.congestionDiv2 = congestionDiv2;
		this.rideNum2 = rideNum2;
		this.rideDiv2 = rideDiv2;
		this.nextStopId2 = nextStopId2;
		this.nextStopOrd2 = nextStopOrd2;
		this.nextStopSec2 = nextStopSec2;
		this.nextStopSpd2 = nextStopSpd2;
		this.mainStopOrd2 = mainStopOrd2;
		this.mainStopSec2 = mainStopSec2;
		this.mainStopId2 = mainStopId2;
		this.main2StopOrd2 = main2StopOrd2;
		this.main2StopSec2 = main2StopSec2;
		this.main2StopId2 = main2StopId2;
		this.main3StopOrd2 = main3StopOrd2;
		this.main3StopSec2 = main3StopSec2;
		this.main3StopId2 = main3StopId2;
	}
}
