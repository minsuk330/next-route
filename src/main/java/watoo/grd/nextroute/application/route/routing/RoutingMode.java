package watoo.grd.nextroute.application.route.routing;

/**
 * provider 무관 이동수단. 원천 세부타입(고속철도 등)은 {@code sourceDetailType} 문자열로 별도 보존하고
 * 여기서 잃지 않는다. 미지원 수단은 {@link #UNSUPPORTED} 로 명시하며 bus 로 변환하지 않는다.
 */
public enum RoutingMode {
    WALK,
    BUS,
    SUBWAY,
    RAIL,
    UNSUPPORTED
}
