package watoo.grd.nextroute.application.arrival.port.out;

import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;

/**
 * 경기도 버스도착 조회. 서울과 달리 순번(ord) 없이 (정류장, 노선)만으로 조회된다.
 * id 는 OTP {@code BS_TAGO_GGB}/{@code BR_TAGO_GGB} 뒤 9자리와 같다.
 *
 * <p>결과는 {@link BusQueryResult}: OK(빈 리스트 = 도착 정보 없음) 와 BLOCKED/ERROR(조회 못 함)를 구분한다.
 */
public interface GyeonggiBusArrivalPort {

    BusQueryResult<GyeonggiBusArrival> getArrival(String stationId, String routeId);

    /**
     * @param chargeQuota false 면 어댑터가 일일 몫(gg-arrival)을 세지 않는다 — 재탐색처럼 부르는 쪽이 자기 버킷을 이미
     *                    점유한 경우(bus-multi-instance PR-4). 공유 플래그가 꺼져 있으면 의미 없다.
     */
    default BusQueryResult<GyeonggiBusArrival> getArrival(String stationId, String routeId, boolean chargeQuota) {
        return getArrival(stationId, routeId);
    }
}
