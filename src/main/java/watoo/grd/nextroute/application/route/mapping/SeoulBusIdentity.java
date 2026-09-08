package watoo.grd.nextroute.application.route.mapping;

/**
 * 서울 버스 식별. OTP {@code 1:BS_TAGO_SEB<stop_id>} / {@code 1:BR_TAGO_SEB<route_id>} 에서
 * prefix 를 떼어 우리 DB(bus_stop.stop_id, bus_route.route_id)와 존재 검증한 값.
 *
 * @param routeName OTP shortName(버스번호, 예 "143"). DB 아닌 OTP 원천값을 그대로 보존
 */
public record SeoulBusIdentity(String stopId, String routeId, String routeName)
        implements SeoulTransitIdentity {
}
