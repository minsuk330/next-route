package watoo.grd.nextroute.application.bus.exception;

/** 일회성/정적 서울버스 API 호출 중 provider 일일 호출 한도(code 7)를 만난 경우. */
public class BusApiLimitExceededException extends RuntimeException {

	public BusApiLimitExceededException(String message) {
		super(message);
	}
}
