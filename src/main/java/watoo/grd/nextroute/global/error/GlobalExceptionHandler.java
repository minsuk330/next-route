package watoo.grd.nextroute.global.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import watoo.grd.nextroute.global.error.code.CommonErrorCode;

@RestControllerAdvice
@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  /**
   * @RequestBody DTO의 @Valid 검증 실패.
   *
   * 예:
   * {
   *   "code": "COMMON_VALIDATION_ERROR",
   *   "fieldErrors": [
   *     {"field": "routeRevision", "message": "1 이상이어야 합니다"}
   *   ]
   * }
   */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request
  ) {
    List<FieldErrorResponse> fieldErrors =
        ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(error -> new FieldErrorResponse(
                error.getField(),
                error.getDefaultMessage()
            ))
            .toList();

    ErrorResponse<Void> response = new ErrorResponse<>(
        CommonErrorCode.INVALID_REQUEST.getCode(),
        CommonErrorCode.INVALID_REQUEST.getMessage(),
        getPath(request),
        fieldErrors,
        null
    );
    return validationResponse(fieldErrors, headers, request);
  }

  /**
   * Controller method parameter 검증 실패.
   *
   * 예:
   * @RequestParam @Positive Long id
   * @PathVariable @Min(1) Long journeyId
   */
  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request
  ) {
    List<FieldErrorResponse> fieldErrors =
        exception.getParameterValidationResults()
            .stream()
            .flatMap(result -> {
              String parameterName = Optional.ofNullable(
                  result.getMethodParameter().getParameterName()
              ).orElse(
                  "argument"
                      + result.getMethodParameter().getParameterIndex()
              );

              return result.getResolvableErrors()
                  .stream()
                  .map(error -> new FieldErrorResponse(
                      parameterName,
                      Optional.ofNullable(error.getDefaultMessage())
                          .orElse("올바르지 않은 값입니다.")
                  ));
            })
            .distinct()
            .toList();

    return validationResponse(fieldErrors, headers, request);
  }

  /**
   * JSON 문법 오류, enum 변환 실패, 타입 불일치 등.
   *
   * Jackson의 구체적인 오류 메시지는 내부 클래스 구조가 노출될 수 있어
   * 클라이언트에 전달하지 않는다.
   */
  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request
  ) {
    return errorResponse(
        CommonErrorCode.INVALID_REQUEST,
        headers,
        request
    );
  }

  /**
   * Service method 등에 적용된 Jakarta Validation 실패.
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse<Void>> handleConstraintViolation(
      ConstraintViolationException exception,
      HttpServletRequest request
  ) {
    List<FieldErrorResponse> fieldErrors = exception.getConstraintViolations()
        .stream()
        .map(violation -> new FieldErrorResponse(
            violation.getPropertyPath().toString(),
            violation.getMessage()
        ))
        .distinct()
        .toList();

    CommonErrorCode errorCode = CommonErrorCode.VALIDATION_ERROR;

    return ResponseEntity
        .status(errorCode.getHttpStatus())
        .body(new ErrorResponse<>(
            errorCode.getCode(),
            errorCode.getMessage(),
            request.getRequestURI(),
            fieldErrors,
            null
        ));
  }

  /** 예상된 비즈니스 오류를 ErrorCode 기반 공통 응답으로 변환한다. */
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse<Void>> handleBusinessException(
      BusinessException exception,
      HttpServletRequest request
  ) {
    ErrorCode errorCode = exception.getErrorCode();
    return ResponseEntity
        .status(errorCode.getHttpStatus())
        .body(ErrorResponse.of(errorCode, request.getRequestURI()));
  }
  /**
   * query/path parameter 타입 변환 실패.
   *
   * 예: journeyId 자리에 문자열 전달.
   */
  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request
  ) {
    return errorResponse(
        CommonErrorCode.INVALID_REQUEST,
        headers,
        request
    );
  }

  /**
   * 존재하지 않는 API/resource.
   */
  @Override
  protected ResponseEntity<Object> handleNoResourceFoundException(
      NoResourceFoundException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request
  ) {
    return errorResponse(
        CommonErrorCode.NOT_FOUND,
        headers,
        request
    );
  }


  /**
   * 지원하지 않는 HTTP method.
   *
   * 예: POST endpoint에 PUT 요청.
   */
  @Override
  protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
      HttpRequestMethodNotSupportedException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request
  ) {
    return errorResponse(
        CommonErrorCode.METHOD_NOT_ALLOWED,
        headers,
        request
    );
  }

  /**
   * 지원하지 않는 Content-Type.
   */
  @Override
  protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request
  ) {
    return errorResponse(
        CommonErrorCode.UNSUPPORTED_MEDIA_TYPE,
        headers,
        request
    );
  }
  /**
   * ResponseEntityExceptionHandler가 잡았지만 별도 override하지 않은
   * Spring MVC 예외를 ErrorResponse 형식으로 통일한다.
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request
  ) {
    ErrorCode errorCode;

    if (status.is5xxServerError()) {
      errorCode = CommonErrorCode.INTERNAL_SERVER_ERROR;
      log.error("Unhandled Spring MVC exception", exception);
    } else {
      errorCode = CommonErrorCode.INVALID_REQUEST;
    }

    return errorResponse(errorCode, headers, request);
  }

  private ResponseEntity<Object> errorResponse(
      ErrorCode errorCode,
      HttpHeaders headers,
      WebRequest request
  ) {
    return new ResponseEntity<>(
        ErrorResponse.of(
            errorCode,
            getPath(request)
        ),
        headers,
        errorCode.getHttpStatus()
    );
  }



  /**
   * 어디에서도 예상하지 못한 예외.
   *
   * 클라이언트에는 공통 메시지만 전달하고 stack trace는 서버 로그에만 남긴다.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse<Void>> handleUnexpectedException(
      Exception exception,
      HttpServletRequest request
  ) {
    log.error(
        "Unhandled exception. method={}, path={}",
        request.getMethod(),
        request.getRequestURI(),
        exception
    );

    CommonErrorCode errorCode =
        CommonErrorCode.INTERNAL_SERVER_ERROR;

    return ResponseEntity
        .status(errorCode.getHttpStatus())
        .body(ErrorResponse.of(
            errorCode,
            request.getRequestURI()
        ));
  }








  private String getPath(WebRequest request) {
    if (request instanceof ServletWebRequest servletWebRequest) {
      return servletWebRequest
          .getRequest()
          .getRequestURI();
    }
    return request.getDescription(false);
  }



  //JSON 파싱 실패
  //예상못한 오류



  private ResponseEntity<Object> validationResponse(
      List<FieldErrorResponse> fieldErrors,
      HttpHeaders headers,
      WebRequest request
  ) {
    CommonErrorCode errorCode =
        CommonErrorCode.VALIDATION_ERROR;

    ErrorResponse<Void> body = new ErrorResponse<>(
        errorCode.getCode(),
        errorCode.getMessage(),
        getPath(request),
        fieldErrors,
        null
    );

    return new ResponseEntity<>(
        body,
        headers,
        errorCode.getHttpStatus()
    );
  }

}
