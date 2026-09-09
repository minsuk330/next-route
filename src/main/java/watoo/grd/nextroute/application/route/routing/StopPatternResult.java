package watoo.grd.nextroute.application.route.routing;

import java.util.List;

/**
 * {@link StopPatternPort} 조회 결과. <b>정상 0건과 장애를 구분한다</b>(codex 리뷰 B7).
 *
 * <p>빈 {@code List} 만 돌려주면 소비처(PR4)가 "이 정류장엔 대안이 없다"와 "OTP 가 죽었다/시간이
 * 없었다"를 구분하지 못해, 장애 때 대안을 조용히 빈손으로 만든다. {@code OK} 면 {@code patterns} 가
 * 답(0건 포함), {@code TIMEOUT}/{@code ERROR} 면 소비처가 저장값 폴백 등으로 처리한다.
 */
public record StopPatternResult(Status status, List<StopPattern> patterns) {

    public enum Status { OK, TIMEOUT, ERROR }

    public StopPatternResult {
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
    }

    public static StopPatternResult ok(List<StopPattern> patterns) {
        return new StopPatternResult(Status.OK, patterns);
    }

    public static StopPatternResult timeout() {
        return new StopPatternResult(Status.TIMEOUT, List.of());
    }

    public static StopPatternResult error() {
        return new StopPatternResult(Status.ERROR, List.of());
    }

    public boolean isOk() {
        return status == Status.OK;
    }
}
