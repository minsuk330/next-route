package watoo.grd.nextroute.application.arrival.dto;

import lombok.Builder;

/**
 * 정류장 단위 도착(#577)의 노선 한 줄. 노선 단위 {@link BusArrivalResponse} 와 같은 칸을 같은 뜻으로 주고, 노선 이름과
 * 유형을 더한다. 공급자마다 노선 유형 코드표가 달라 {@code routeTypeSource} 로 어느 표인지 밝힌다.
 *
 * @param routeId         공급자 노선 id(서울 busRouteId, 경기 routeId, TAGO routeid). 앱이 노선을 맞추는 키
 * @param routeName       노선 번호
 * @param routeType       원본 코드(서울 routeType, 경기 routeTypeCd). TAGO 는 숫자 코드가 없어 null
 * @param routeTypeSource {@code SEOUL} / {@code GG} / {@code TAGO}
 * @param routeTypeName   사람 말("간선", "일반", TAGO 는 routetp 원문)
 * @param direction       표시용 방향(서울 adirection 종점 쪽, 경기 routeDestName). TAGO null
 * @param predictTime1    남은 초. 버스가 없으면 null
 */
@Builder(toBuilder = true)
public record BusStopArrivalResponse(
        String routeId,
        String routeName,
        Integer routeType,
        String routeTypeSource,
        String routeTypeName,
        String direction,
        String arrivalMsg1,
        Integer predictTime1,
        Integer congestionNum1,
        Integer stopsAway1,
        String vehicleId1,
        String arrivalMsg2,
        Integer predictTime2,
        Integer congestionNum2,
        Integer stopsAway2,
        String vehicleId2
) {
}
