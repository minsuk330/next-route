package watoo.grd.nextroute.global.error;

import org.springframework.http.HttpStatus;

public interface ErrorCode {

  String getCode();
  String getMessage();
  HttpStatus getHttpStatus();

}
