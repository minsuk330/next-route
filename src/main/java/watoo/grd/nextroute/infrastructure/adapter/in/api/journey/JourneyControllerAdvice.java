package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.exception.ActiveJourneyExistsException;
import watoo.grd.nextroute.application.journey.exception.ProgressTimestampConflictException;
import watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException;
import watoo.grd.nextroute.global.error.ErrorResponse;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Instant;

/** 여정 도메인의 예외를 공통 오류 응답으로 변환한다. */
@RestControllerAdvice(basePackageClasses = JourneyController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JourneyControllerAdvice {

  /** 현재 여정을 details에 담아 앱이 이어서 보기를 바로 그릴 수 있게 한다. */
  @ExceptionHandler(ActiveJourneyExistsException.class)
  public ResponseEntity<ErrorResponse<JourneySnapshot>> handleActiveExists(
      ActiveJourneyExistsException exception,
      HttpServletRequest request) {
    JourneyErrorCode errorCode = JourneyErrorCode.ACTIVE_JOURNEY_EXISTS;
    return ResponseEntity.status(errorCode.getHttpStatus())
        .body(ErrorResponse.of(errorCode, request.getRequestURI(), exception.getCurrent()));
  }

  /** 충돌한 관측 시각을 details에 담는다. 앱이 어느 sample 이 문제였는지 알 수 있어야 한다. */
  @ExceptionHandler(ProgressTimestampConflictException.class)
  public ResponseEntity<ErrorResponse<Instant>> handleProgressConflict(
      ProgressTimestampConflictException exception,
      HttpServletRequest request) {
    JourneyErrorCode errorCode = JourneyErrorCode.PROGRESS_TIMESTAMP_CONFLICT;
    return ResponseEntity.status(errorCode.getHttpStatus())
        .body(ErrorResponse.of(errorCode, request.getRequestURI(), exception.getObservedAt()));
  }

  /** 현재 개정본을 details에 담아 앱이 좌표를 곧바로 다시 받을 수 있게 한다. */
  @ExceptionHandler(StaleRouteRevisionException.class)
  public ResponseEntity<ErrorResponse<Integer>> handleStaleRevision(
      StaleRouteRevisionException exception,
      HttpServletRequest request) {
    JourneyErrorCode errorCode = JourneyErrorCode.STALE_ROUTE_REVISION;
    return ResponseEntity.status(errorCode.getHttpStatus())
        .body(ErrorResponse.of(errorCode, request.getRequestURI(),
            exception.getCurrentRouteRevision()));
  }

  /**
   * 고정 행 잠금을 대기 상한 안에 못 잡았다. <b>재시도 가능한 실패로 답한다.</b>
   *
   * <p>5xx 로 내보내면 앱은 "서버가 고장 났다"로 읽고 백오프를 길게 잡는다. 실제로는 판정 워커가
   * 그 행을 잠깐 잡고 있을 뿐이라 곧바로 다시 보내는 편이 맞다. 중복 전송은 감사행 유일성이
   * 흡수하므로 재시도가 상태를 어지럽히지 않는다.
   */
  @ExceptionHandler(PessimisticLockingFailureException.class)
  public ResponseEntity<ErrorResponse<Void>> handleLockBusy(HttpServletRequest request) {
    JourneyErrorCode errorCode = JourneyErrorCode.SUBWAY_BINDING_BUSY;
    return ResponseEntity.status(errorCode.getHttpStatus())
        .body(ErrorResponse.of(errorCode, request.getRequestURI()));
  }

}
