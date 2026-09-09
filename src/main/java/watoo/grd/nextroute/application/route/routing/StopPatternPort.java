package watoo.grd.nextroute.application.route.routing;

/**
 * OTP 에 "이 정류장을 지나는 노선 pattern 이 무엇인가"를 묻는 포트(national-otp P2-F PR4b).
 *
 * <p>주행 중 재탐색 ③(같은 정류장 다른 노선)이 저장 후보 대신 실시간 pattern 에서 대안을 만들 때
 * 쓴다. 이 PR(PR-1)에서는 어댑터만 두고 <b>배선하지 않는다</b> — 소비는 PR-4.
 */
public interface StopPatternPort {

    /**
     * 정류장 하나를 지나는 모든 노선 pattern.
     *
     * @param stopGtfsId OTP 정류장 id (예 {@code 1:BS_TAGO_SEB101000041})
     * @param deadline   호출별 read timeout 근거(남은 예산). 소진 시 {@link StopPatternResult#timeout()}
     * @return 성공(0건 포함)/timeout/error 를 구분한 결과. 절대 예외로 던지지 않는다
     */
    StopPatternResult patternsThroughStop(String stopGtfsId, RoutingDeadline deadline);
}
