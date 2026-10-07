package watoo.grd.nextroute.global.error.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import watoo.grd.nextroute.global.error.ErrorCode;

@RequiredArgsConstructor
@Getter
public enum SubwayErrorCode implements ErrorCode {

  SUBWAY_STATION_NOT_FOUND("SUBWAY_STATION_NOT_FOUND", "지하철역을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
  /** 실시간 방향 값(상행·하행·내선·외선) 밖이거나, 그 노선에 그 방향이 없다. */
  SUBWAY_DIRECTION_INVALID("SUBWAY_DIRECTION_INVALID", "이 노선에서 쓸 수 없는 방향입니다.", HttpStatus.BAD_REQUEST),
  /** 역은 있지만 노선 운행 데이터(OTP)에 그 역이 없다. 예: 4호선 진접 연장 등 데이터 갭. */
  SUBWAY_STATION_NOT_ON_ROUTE("SUBWAY_STATION_NOT_ON_ROUTE", "이 역의 운행 정보가 없습니다.", HttpStatus.UNPROCESSABLE_ENTITY),
  /** 노선 운행 데이터를 가져오지 못했고 이전 값도 없다. 잠시 뒤 다시 시도하면 된다. */
  SUBWAY_ROUTE_UNAVAILABLE("SUBWAY_ROUTE_UNAVAILABLE", "노선 정보를 잠시 가져올 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE);

  private final String code;
  private final String message;
  private final HttpStatus httpStatus;
}
