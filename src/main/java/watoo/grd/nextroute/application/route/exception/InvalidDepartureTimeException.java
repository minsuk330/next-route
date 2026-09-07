package watoo.grd.nextroute.application.route.exception;

/**
 * departureAt 입력 오류(형식 불량 또는 offset 없음). 무경로/timeout 이 아니라 <b>요청 자체의 문제</b>이므로
 * 400 으로 매핑한다(P2-A: offset 없는 문자열=입력 오류).
 */
public class InvalidDepartureTimeException extends RuntimeException {

    public InvalidDepartureTimeException(String message) {
        super(message);
    }
}
