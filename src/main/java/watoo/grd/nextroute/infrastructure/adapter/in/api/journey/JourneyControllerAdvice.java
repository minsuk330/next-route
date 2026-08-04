package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.exception.ActiveJourneyExistsException;
import watoo.grd.nextroute.global.error.ErrorResponse;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

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

}
