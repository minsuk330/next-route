package watoo.grd.nextroute.application.arrival.dto;

/**
 * 서울 {@code stationinfo/getStationByUid} 의 노선 한 행. 원본 단위 그대로(초, 순번, 차량 id "0" 은 없음).
 */
public record SeoulStationArrival(
        String busRouteId,
        String routeName,
        Integer routeType,
        String stId,
        Integer staOrd,
        String arrivalMsg1,
        Integer traTime1,
        String vehId1,
        Integer sectOrd1,
        Integer congestion1,
        String arrivalMsg2,
        Integer traTime2,
        String vehId2,
        Integer sectOrd2,
        Integer congestion2,
        String adirection
) {
}
