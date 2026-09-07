package watoo.grd.nextroute.application.route.routing;

/**
 * 도보 turn. OTP {@code steps} 구조(distance/relativeDirection/streetName)에 맞춘다.
 * 기존 legacy {@code WalkStep} 로의 변환은 LegacyRouteResponseMapper 가 한다(구조가 달라 직결 금지).
 */
public record RoutingWalkStep(
        double distanceMeters,
        String relativeDirection,
        String streetName
) {
}
