package watoo.grd.nextroute.application.bus.dto;

public record BusArrivalInfo(
		String routeId,
		String stopId,
		Integer seq,
		Integer predictTime1,
		Integer sectionTime1,
		Double sectionSpeed1,
		String isArrive1,
		String vehicleId1,
		String plateNo1,
		Integer predictTime2,
		Integer sectionTime2,
		Double sectionSpeed2,
		String isArrive2,
		String vehicleId2,
		String plateNo2,
		String arrivalMsg1,
		String arrivalMsg2,
		Integer sectionOrder1,
		Integer sectionOrder2,
		String stationName1,
		String stationName2
) {}
