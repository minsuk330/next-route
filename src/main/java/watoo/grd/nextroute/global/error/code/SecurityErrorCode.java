package watoo.grd.nextroute.global.error.code;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import watoo.grd.nextroute.global.error.ErrorCode;

@AllArgsConstructor
@Getter
public enum SecurityErrorCode implements ErrorCode {

  UNAUTHENTICATED(
      "SECURITY_UNAUTHENTICATED",
      "인증이 필요합니다.",
      HttpStatus.UNAUTHORIZED
  ),
  ACCESS_DENIED(
      "SECURITY_ACCESS_DENIED",
      "접근 권한이 없습니다.",
      HttpStatus.FORBIDDEN
  );

  private final String code;
  private final String message;
  private final HttpStatus httpStatus;

}
