package watoo.grd.nextroute.application.notification.exception;

/**
 * 알림 스케줄 수정 불가(비-ACTIVE 상태 또는 동시 수정 낙관락 충돌). HTTP 409 매핑.
 * IllegalStateException(422) 과 구분하기 위한 전용 예외.
 */
public class ScheduleNotEditableException extends RuntimeException {
    public ScheduleNotEditableException(String message) {
        super(message);
    }
}
