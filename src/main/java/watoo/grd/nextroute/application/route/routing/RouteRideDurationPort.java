package watoo.grd.nextroute.application.route.routing;

/**
 * OTP 에 "이 노선을 타고 board→alight 를 가는 데 걸리는 <b>시간표 소요(초)</b>가 얼마인가"를 묻는
 * 포트(national-otp P2-F PR4c).
 *
 * <p>주행 중 재탐색 ③(같은 정류장 다른 노선) 후보의 목적지 ETA 를 잴 때, 타고 가는 소요를 원래
 * 선택 노선 값이 아니라 <b>후보 노선의 시간표 소요</b>로 바꾸기 위한 재료다. 값은 OTP
 * {@code Trip.stoptimes} 의 {@code scheduledArrival}/{@code scheduledDeparture} 차이 —
 * <b>실시간 실주행이 아니라 시간표값</b>이다(명칭 정직화).
 *
 * <p>이 PR(PR4c-1)에서는 어댑터만 두고 <b>배선하지 않는다</b> — 소비는 PR4c-3.
 */
public interface RouteRideDurationPort {

    /**
     * 한 노선이 board→alight 를 타고 가는 시간표 소요(초).
     *
     * @param routeGtfsId OTP route id (예 {@code 1:BR_TAGO_SEB100100022})
     * @param boardGtfsId 승차 정류장 OTP gtfsId
     * @param alightGtfsId 하차 정류장 OTP gtfsId
     * @param deadline    호출별 read timeout 근거(남은 예산). 소진 시
     *                    {@link RideDurationResult#timeout()}
     * @return 성공(초)/미매칭(정상, 이 노선이 이 구간을 안 감)/timeout/error 를 구분한 결과.
     *         절대 예외로 던지지 않는다
     */
    RideDurationResult rideDurationSeconds(String routeGtfsId, String boardGtfsId,
                                           String alightGtfsId, RoutingDeadline deadline);
}
