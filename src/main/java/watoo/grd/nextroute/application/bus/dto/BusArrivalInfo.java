package watoo.grd.nextroute.application.bus.dto;

public record BusArrivalInfo(
		// 공통
		String routeId,
		String stopId,
		String arsId,
		Integer seq,
		String direction,
		Integer routeType,
		Integer term,
		String dataTimestamp,
		String detourYn,
		String nextBusYn,

		// 첫 번째 버스
		String arrivalMsg1,
		String vehicleId1,
		String plateNo1,
		Integer busType1,
		Integer sectionOrder1,
		String stationName1,
		String isArrive1,
		String isLast1,
		String isFull1,

		Integer predictTime1,
		Integer kalPredictTime1,
		Integer neuPredictTime1,
		Integer goalTime1,

		Double avgCoefficient1,
		Double expCoefficient1,
		Double kalCoefficient1,
		Double neuCoefficient1,

		Integer sectionTime1,
		Double sectionSpeed1,

		Integer congestionNum1,
		Integer congestionDiv1,
		Integer rideNum1,
		Integer rideDiv1,

		String nextStopId1,
		Integer nextStopOrd1,
		Integer nextStopSec1,
		Integer nextStopSpd1,

		Integer mainStopOrd1,
		Integer mainStopSec1,
		String mainStopId1,

		Integer main2StopOrd1,
		Integer main2StopSec1,
		String main2StopId1,

		Integer main3StopOrd1,
		Integer main3StopSec1,
		String main3StopId1,

		// 두 번째 버스
		String arrivalMsg2,
		String vehicleId2,
		String plateNo2,
		Integer busType2,
		Integer sectionOrder2,
		String stationName2,
		String isArrive2,
		String isLast2,
		String isFull2,

		Integer predictTime2,
		Integer kalPredictTime2,
		Integer neuPredictTime2,
		Integer goalTime2,

		Double avgCoefficient2,
		Double expCoefficient2,
		Double kalCoefficient2,
		Double neuCoefficient2,

		Integer sectionTime2,
		Double sectionSpeed2,

		Integer congestionNum2,
		Integer congestionDiv2,
		Integer rideNum2,
		Integer rideDiv2,

		String nextStopId2,
		Integer nextStopOrd2,
		Integer nextStopSec2,
		Integer nextStopSpd2,

		Integer mainStopOrd2,
		Integer mainStopSec2,
		String mainStopId2,

		Integer main2StopOrd2,
		Integer main2StopSec2,
		String main2StopId2,

		Integer main3StopOrd2,
		Integer main3StopSec2,
		String main3StopId2
) {}
