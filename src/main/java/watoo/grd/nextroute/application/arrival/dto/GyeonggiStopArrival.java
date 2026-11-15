package watoo.grd.nextroute.application.arrival.dto;

/**
 * 경기 {@code getBusArrivalListv2}(정류장 단위)의 노선 한 행: 노선 단위 원본과 같은 칸({@link GyeonggiBusArrival}) +
 * 노선 이름, 유형, 종점.
 */
public record GyeonggiStopArrival(GyeonggiBusArrival arrival, String routeName, Integer routeTypeCd, String routeDestName) {
}
