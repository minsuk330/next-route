package watoo.grd.nextroute.application.route.routing;

/**
 * 실패 결과(first-class). OUT_OF_SCOPE(범위 밖)를 NO_ROUTE(범위 안 무경로)·TIMEOUT·엔진장애·입력오류와
 * 구분한다. OTP {@code routingErrors[].code == OUTSIDE_BOUNDS} → OUT_OF_SCOPE(P1 실측).
 */
public record RoutingFailure(Kind kind, String code, String message) implements RoutingOutcome {

    public enum Kind {
        OUT_OF_SCOPE,
        NO_ROUTE,
        TIMEOUT,
        ENGINE_ERROR,
        INPUT_ERROR
    }

    public static RoutingFailure of(Kind kind, String code, String message) {
        return new RoutingFailure(kind, code, message);
    }

    @Override
    public boolean success() {
        return false;
    }
}
