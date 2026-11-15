package watoo.grd.nextroute.application.arrival.dto;

/**
 * TAGO {@code getSttnAcctoArvlPrearngeInfoList}(정류장 단위)의 버스 한 대: 노선 단위 원본과 같은 칸
 * ({@link TagoBusArrival}) + 노선 번호와 유형 글자.
 */
public record TagoStopArrival(TagoBusArrival bus, String routeNo, String routeTp) {
}
