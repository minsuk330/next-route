package watoo.grd.nextroute.application.bus.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record BusArrivalCandidate(
		String routeId,
		String routeAbrv,
		String routeName,
		String stopId,
		String arsId,
		String stopName,
		Integer seq,
		String direction,
		Integer routeType,
		Integer term,
		String dataTimestamp,
		String detourYn,
		String nextBusYn,
		String firstBusTime,
		String lastBusTime,
		Integer arrivalOrder,
		String arrivalMsg,
		String vehicleId,
		String plainNo,
		String vehicleIdentity,
		VehicleIdentityType vehicleIdentityType,
		Integer busType,
		Integer sectionOrder,
		String stationName,
		String isArrive,
		String isLast,
		String isFull,
		Integer predictTime,
		Integer kalPredictTime,
		Integer neuPredictTime,
		Integer goalTime,
		Double avgCoefficient,
		Double expCoefficient,
		Double kalCoefficient,
		Double neuCoefficient,
		Integer sectionTime,
		Double sectionSpeed,
		Integer congestionNum,
		Integer congestionDiv,
		Integer rideNum,
		Integer rideDiv,
		String nextStopId,
		Integer nextStopOrd,
		Integer nextStopSec,
		Integer nextStopSpd,
		Integer mainStopOrd,
		Integer mainStopSec,
		String mainStopId,
		Integer main2StopOrd,
		Integer main2StopSec,
		String main2StopId,
		Integer main3StopOrd,
		Integer main3StopSec,
		String main3StopId,
		LocalDateTime collectedAt
) {

	public static List<BusArrivalCandidate> from(BusArrivalInfo info, LocalDateTime collectedAt) {
		List<BusArrivalCandidate> candidates = new ArrayList<>(2);
		first(info, collectedAt).ifPresent(candidates::add);
		second(info, collectedAt).ifPresent(candidates::add);
		return candidates;
	}

	public String identityKey() {
		return switch (vehicleIdentityType) {
			case VEH_ID -> "veh:" + vehicleIdentity;
			case PLAIN_NO -> "plate:" + vehicleIdentity;
		};
	}

	private static Optional<BusArrivalCandidate> first(BusArrivalInfo info, LocalDateTime collectedAt) {
		return create(
				info,
				collectedAt,
				1,
				info.arrivalMsg1(),
				info.vehicleId1(),
				info.plateNo1(),
				info.busType1(),
				info.sectionOrder1(),
				info.stationName1(),
				info.isArrive1(),
				info.isLast1(),
				info.isFull1(),
				info.predictTime1(),
				info.kalPredictTime1(),
				info.neuPredictTime1(),
				info.goalTime1(),
				info.avgCoefficient1(),
				info.expCoefficient1(),
				info.kalCoefficient1(),
				info.neuCoefficient1(),
				info.sectionTime1(),
				info.sectionSpeed1(),
				info.congestionNum1(),
				info.congestionDiv1(),
				info.rideNum1(),
				info.rideDiv1(),
				info.nextStopId1(),
				info.nextStopOrd1(),
				info.nextStopSec1(),
				info.nextStopSpd1(),
				info.mainStopOrd1(),
				info.mainStopSec1(),
				info.mainStopId1(),
				info.main2StopOrd1(),
				info.main2StopSec1(),
				info.main2StopId1(),
				info.main3StopOrd1(),
				info.main3StopSec1(),
				info.main3StopId1()
		);
	}

	private static Optional<BusArrivalCandidate> second(BusArrivalInfo info, LocalDateTime collectedAt) {
		return create(
				info,
				collectedAt,
				2,
				info.arrivalMsg2(),
				info.vehicleId2(),
				info.plateNo2(),
				info.busType2(),
				info.sectionOrder2(),
				info.stationName2(),
				info.isArrive2(),
				info.isLast2(),
				info.isFull2(),
				info.predictTime2(),
				info.kalPredictTime2(),
				info.neuPredictTime2(),
				info.goalTime2(),
				info.avgCoefficient2(),
				info.expCoefficient2(),
				info.kalCoefficient2(),
				info.neuCoefficient2(),
				info.sectionTime2(),
				info.sectionSpeed2(),
				info.congestionNum2(),
				info.congestionDiv2(),
				info.rideNum2(),
				info.rideDiv2(),
				info.nextStopId2(),
				info.nextStopOrd2(),
				info.nextStopSec2(),
				info.nextStopSpd2(),
				info.mainStopOrd2(),
				info.mainStopSec2(),
				info.mainStopId2(),
				info.main2StopOrd2(),
				info.main2StopSec2(),
				info.main2StopId2(),
				info.main3StopOrd2(),
				info.main3StopSec2(),
				info.main3StopId2()
		);
	}

	private static Optional<BusArrivalCandidate> create(
			BusArrivalInfo info,
			LocalDateTime collectedAt,
			Integer arrivalOrder,
			String arrivalMsg,
			String vehicleId,
			String plainNo,
			Integer busType,
			Integer sectionOrder,
			String stationName,
			String isArrive,
			String isLast,
			String isFull,
			Integer predictTime,
			Integer kalPredictTime,
			Integer neuPredictTime,
			Integer goalTime,
			Double avgCoefficient,
			Double expCoefficient,
			Double kalCoefficient,
			Double neuCoefficient,
			Integer sectionTime,
			Double sectionSpeed,
			Integer congestionNum,
			Integer congestionDiv,
			Integer rideNum,
			Integer rideDiv,
			String nextStopId,
			Integer nextStopOrd,
			Integer nextStopSec,
			Integer nextStopSpd,
			Integer mainStopOrd,
			Integer mainStopSec,
			String mainStopId,
			Integer main2StopOrd,
			Integer main2StopSec,
			String main2StopId,
			Integer main3StopOrd,
			Integer main3StopSec,
			String main3StopId
	) {
		if (!hasText(info.routeId()) || !hasText(info.stopId()) || info.seq() == null) {
			return Optional.empty();
		}

		ResolvedIdentity identity = resolveIdentity(vehicleId, plainNo);
		if (identity == null) {
			return Optional.empty();
		}

		return Optional.of(new BusArrivalCandidate(
				normalize(info.routeId()),
				normalize(info.routeAbrv()),
				normalize(info.routeName()),
				normalize(info.stopId()),
				normalize(info.arsId()),
				normalize(info.stopName()),
				info.seq(),
				normalize(info.direction()),
				info.routeType(),
				info.term(),
				normalize(info.dataTimestamp()),
				normalize(info.detourYn()),
				normalize(info.nextBusYn()),
				normalize(info.firstBusTime()),
				normalize(info.lastBusTime()),
				arrivalOrder,
				normalize(arrivalMsg),
				normalize(vehicleId),
				normalize(plainNo),
				identity.value(),
				identity.type(),
				busType,
				sectionOrder,
				normalize(stationName),
				normalize(isArrive),
				normalize(isLast),
				normalize(isFull),
				predictTime,
				kalPredictTime,
				neuPredictTime,
				goalTime,
				avgCoefficient,
				expCoefficient,
				kalCoefficient,
				neuCoefficient,
				sectionTime,
				sectionSpeed,
				congestionNum,
				congestionDiv,
				rideNum,
				rideDiv,
				normalize(nextStopId),
				nextStopOrd,
				nextStopSec,
				nextStopSpd,
				mainStopOrd,
				mainStopSec,
				normalize(mainStopId),
				main2StopOrd,
				main2StopSec,
				normalize(main2StopId),
				main3StopOrd,
				main3StopSec,
				normalize(main3StopId),
				collectedAt
		));
	}

	private static ResolvedIdentity resolveIdentity(String vehicleId, String plainNo) {
		String normalizedVehicleId = normalize(vehicleId);
		if (isUsableIdentity(normalizedVehicleId)) {
			return new ResolvedIdentity(VehicleIdentityType.VEH_ID, normalizedVehicleId);
		}

		String normalizedPlainNo = normalize(plainNo);
		if (isUsableIdentity(normalizedPlainNo)) {
			return new ResolvedIdentity(VehicleIdentityType.PLAIN_NO, normalizedPlainNo);
		}

		return null;
	}

	private static boolean isUsableIdentity(String value) {
		return hasText(value) && !"0".equals(value);
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}

	private static String normalize(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private record ResolvedIdentity(VehicleIdentityType type, String value) {
	}
}
