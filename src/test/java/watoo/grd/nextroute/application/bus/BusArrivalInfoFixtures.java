package watoo.grd.nextroute.application.bus;

import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;

public final class BusArrivalInfoFixtures {

	private BusArrivalInfoFixtures() {
	}

	/** 첫 버스 kalPredictTime1(초)만 지정한 최소 fixture. */
	public static BusArrivalInfo arrivalWithKalPredict1(Integer kalPredictTime1) {
		return arrivalWithKalPredict(kalPredictTime1, 241);
	}

	/** 첫·둘째 버스 sectionTime(초, traTime) 을 지정한 최소 fixture. 도착 ETA 소스. */
	public static BusArrivalInfo arrivalWithSectionTime(Integer sectionTime1, Integer sectionTime2) {
		return new BusArrivalInfo(
				"100100118", "753", "753",
				"111000299", "12390", "구상동사거리",
				1, "방향", 3, 12,
				"2023-09-27 16:51:36.0", "00", "Y",
				"20230927041000", "20230927222000",
				"곧 도착", "v1", "p1", 0, 3, "이전정류장", "0", "0", "0",
				60, 61, 62, 3600,
				1.1, 1.2, 1.3, 1.4,
				sectionTime1, 14.5,
				10, 4, 12, 2,
				"111000300", 2, 80, 20,
				5, 200, "111000310",
				10, 500, "111000320",
				15, 900, "111000330",
				"3분 후", "v2", "p2", 1, 7, "두번째정류장", "0", "0", "0",
				240, 241, 242, 7200,
				2.1, 2.2, 2.3, 2.4,
				sectionTime2, 13.5,
				20, 4, 24, 2,
				"111000301", 6, 280, 18,
				11, 700, "111000311",
				16, 1000, "111000321",
				21, 1400, "111000331"
		);
	}

	/** 첫·둘째 버스 kalPredictTime(초)을 지정한 최소 fixture. */
	public static BusArrivalInfo arrivalWithKalPredict(Integer kalPredictTime1, Integer kalPredictTime2) {
		return new BusArrivalInfo(
				"100100118", "753", "753",
				"111000299", "12390", "구상동사거리",
				1, "방향", 3, 12,
				"2023-09-27 16:51:36.0", "00", "Y",
				"20230927041000", "20230927222000",
				"곧 도착", "v1", "p1", 0, 3, "이전정류장", "0", "0", "0",
				60, kalPredictTime1, 62, 3600,
				1.1, 1.2, 1.3, 1.4,
				2, 14.5,
				10, 4, 12, 2,
				"111000300", 2, 80, 20,
				5, 200, "111000310",
				10, 500, "111000320",
				15, 900, "111000330",
				"3분 후", "v2", "p2", 1, 7, "두번째정류장", "0", "0", "0",
				240, kalPredictTime2, 242, 7200,
				2.1, 2.2, 2.3, 2.4,
				5, 13.5,
				20, 4, 24, 2,
				"111000301", 6, 280, 18,
				11, 700, "111000311",
				16, 1000, "111000321",
				21, 1400, "111000331"
		);
	}

	public static BusArrivalInfo arrivalInfo(
			String vehicleId1,
			String plainNo1,
			String arrivalMsg1,
			String vehicleId2,
			String plainNo2,
			String arrivalMsg2
	) {
		return new BusArrivalInfo(
				"100100118", "753", "753",
				"111000299", "12390", "구상동사거리",
				1, "방향", 3, 12,
				"2023-09-27 16:51:36.0", "00", "Y",
				"20230927041000", "20230927222000",
				arrivalMsg1, vehicleId1, plainNo1, 0, 3, "이전정류장", "0", "0", "0",
				60, 61, 62, 3600,
				1.1, 1.2, 1.3, 1.4,
				2, 14.5,
				10, 4, 12, 2,
				"111000300", 2, 80, 20,
				5, 200, "111000310",
				10, 500, "111000320",
				15, 900, "111000330",
				arrivalMsg2, vehicleId2, plainNo2, 1, 7, "두번째정류장", "0", "0", "0",
				240, 241, 242, 7200,
				2.1, 2.2, 2.3, 2.4,
				5, 13.5,
				20, 4, 24, 2,
				"111000301", 6, 280, 18,
				11, 700, "111000311",
				16, 1000, "111000321",
				21, 1400, "111000331"
		);
	}
}
