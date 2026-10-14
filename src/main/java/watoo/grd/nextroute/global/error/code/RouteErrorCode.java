package watoo.grd.nextroute.global.error.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import watoo.grd.nextroute.global.error.ErrorCode;

@RequiredArgsConstructor
@Getter
public enum RouteErrorCode implements ErrorCode {

  /** 지금 출발 검색의 후보가 전부 실시간 운행종료 버스 구간을 지나 숨겨졌다(#400). 앱은 "지금은 탈 수 있는 경로가 없어요". */
  ROUTE_ALL_ENDED("ROUTE_ALL_ENDED", "지금은 탈 수 있는 경로가 없어요.", HttpStatus.UNPROCESSABLE_ENTITY);

  private final String code;
  private final String message;
  private final HttpStatus httpStatus;
}
