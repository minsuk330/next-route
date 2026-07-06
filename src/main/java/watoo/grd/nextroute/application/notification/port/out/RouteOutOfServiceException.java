package watoo.grd.nextroute.application.notification.port.out;

/**
 * 후보 경로가 전부 운행불가(첫차 전·막차 후)일 때. 요청은 유효하나 처리 불가 → 422.
 * {@link IllegalStateException} 을 상속해 기존 컨트롤러의 422 매핑을 재사용한다.
 */
public class RouteOutOfServiceException extends IllegalStateException {
    public RouteOutOfServiceException(String message) {
        super(message);
    }
}
