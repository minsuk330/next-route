package watoo.grd.nextroute.application.arrival.port.out;

import java.util.OptionalInt;

/**
 * 경기 도착 조회에 넣을 노선 안 정류장 순번({@code staOrder}). 공급자가 이 값 없이는 조회를 거절한다(2026-11-02 부터 실측).
 */
public interface GyeonggiStopOrderPort {

    /**
     * @return 그 노선에서 정류장 순번이 하나로 정해질 때만 값. 표에 없거나 같은 정류장이 두 번 나와 어느 쪽인지 모르면 빈 값
     */
    OptionalInt orderOf(String stationId, String routeId);
}
