package watoo.grd.nextroute.application.arrival.dto;

/**
 * TAGO 버스도착 API 의 버스 한 대(item 하나). 원천 단위 그대로 둔다.
 * 서울 응답 의미로의 변환은 {@code TagoArrivalMapper} 가 한다.
 *
 * @param arrSeconds  도착 예정(초, 원천 {@code arrtime})
 * @param stopsAway   몇 정류장 전(원천 {@code arrprevstationcnt})
 * @param vehicleType 원천 {@code vehicletp}(예 저상버스)
 */
public record TagoBusArrival(String routeId, Integer arrSeconds, Integer stopsAway, String vehicleType) {
}
