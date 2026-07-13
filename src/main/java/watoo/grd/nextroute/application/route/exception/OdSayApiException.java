package watoo.grd.nextroute.application.route.exception;

import lombok.Getter;

@Getter
public class OdSayApiException extends RuntimeException {

    /**
     * 실패의 성격. 호출자(HTTP 응답 매핑·재시도 판단)가 원인을 구분할 수 있어야 한다.
     * 전부 400 으로만 내보내면 "요청이 잘못됐다"와 "상류가 응답하지 않는다"가 섞인다.
     */
    public enum Kind {
        /** ODsay 가 의미 있는 오류를 돌려준 경우(예: -98 출/도착지 700m 이내). 재시도 대상 아님. */
        BUSINESS,
        /** 시간 예산 안에 응답을 받지 못함. */
        TIMEOUT,
        /** 연결 실패·5xx·파싱 실패 등 상류 장애. */
        UPSTREAM
    }

    private final int errorCode;
    private final Kind kind;

    public OdSayApiException(int errorCode, String message) {
        this(errorCode, message, Kind.BUSINESS);
    }

    public OdSayApiException(int errorCode, String message, Kind kind) {
        super(message);
        this.errorCode = errorCode;
        this.kind = kind;
    }

    public static OdSayApiException timeout(String message) {
        return new OdSayApiException(-1, message, Kind.TIMEOUT);
    }

    public static OdSayApiException upstream(String message) {
        return new OdSayApiException(-1, message, Kind.UPSTREAM);
    }
}
