package watoo.grd.nextroute.application.route.routing;

/**
 * {@link RouteRideDurationPort} 조회 결과. <b>정상 미매칭과 장애를 구분한다</b>
 * ({@link StopPatternResult} 와 같은 철학).
 *
 * <p>{@code NOT_FOUND}(이 노선이 board→alight 를 지나지 않음)와 {@code TIMEOUT}/{@code ERROR}(예산
 * 소진·OTP 장애)를 섞으면, 소비처(PR4c-3)가 "이 노선엔 이 구간이 없다"와 "OTP 가 죽었다"를 구분하지
 * 못한다. 공정성 게이트는 baseline·후보 <b>둘 다 {@code OK} + 양수 delta</b>일 때만 실제 제안으로
 * 승격하므로, 이 구분이 승격/폴백을 가른다.
 *
 * @param status  결과 종류
 * @param seconds {@code OK} 일 때 board→alight 시간표 소요(초, 양수). 그 외 null
 */
public record RideDurationResult(Status status, Integer seconds) {

    public enum Status { OK, NOT_FOUND, TIMEOUT, ERROR }

    public static RideDurationResult ok(int seconds) {
        return new RideDurationResult(Status.OK, seconds);
    }

    public static RideDurationResult notFound() {
        return new RideDurationResult(Status.NOT_FOUND, null);
    }

    public static RideDurationResult timeout() {
        return new RideDurationResult(Status.TIMEOUT, null);
    }

    public static RideDurationResult error() {
        return new RideDurationResult(Status.ERROR, null);
    }

    public boolean isOk() {
        return status == Status.OK;
    }
}
