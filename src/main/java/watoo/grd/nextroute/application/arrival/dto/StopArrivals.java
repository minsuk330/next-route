package watoo.grd.nextroute.application.arrival.dto;

import java.util.List;

/**
 * 정류장 여러 곳 묶음 응답(#577)의 정류장 하나.
 *
 * @param status   공급자 하나라도 조회에 성공하면 {@code OK}. 모두 실패면 그 실패 종류. 어느 카탈로그에도 없는 id 는
 *                 {@code UNKNOWN_STOP}(공급자를 부르지 않았다)
 * @param arrivals 노선별 줄. 실패한 공급자의 줄은 빠진다
 */
public record StopArrivals(String stopId, Status status, List<BusStopArrivalResponse> arrivals) {

    public enum Status { OK, LIMITED, BLOCKED, ERROR, UNKNOWN_STOP }
}
